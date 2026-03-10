package com.sc.lcm.core.service;

import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.api.solver.SolverJob;
import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.LcmSolution;
import com.sc.lcm.core.domain.Node;
import com.sc.lcm.core.domain.Satellite;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 分区调度服务 (P2-4)
 * 按 Zone 将节点分组并行求解，提升大规模调度性能
 * 
 * 优势:
 * - 万级节点场景下，将问题分解为多个子问题并行求解
 * - 每个 Zone 独立求解，减少单次求解的搜索空间
 * - 按地理位置或网络拓扑分区，减少跨区域调度
 */
@ApplicationScoped
@Slf4j
public class PartitionedSchedulingService {

    @Inject
    SolverManager<LcmSolution, UUID> solverManager;

    @Inject
    NodeSpecsProvider nodeSpecsProvider;

    @Inject
    @Channel("job-queue-out")
    Emitter<Job> jobEmitter;

    @Inject
    Vertx vertx;

    /** 并行求解的线程池 */
    private final ExecutorService zoneExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors());

    @PreDestroy
    void shutdown() {
        zoneExecutor.shutdownNow();
    }

    /**
     * 按 Zone 分区调度 - 响应式入口
     * 
     * @param job 要调度的作业
     * @return 调度结果 (JobId -> 分配的 Node)
     */
    public Uni<Map<String, Node>> scheduleByZone(Job job) {
        Job detachedJob = cloneJob(job);

        return loadNodesByZone()
                .chain(nodesByZone -> {
                    log.info("Partitioned scheduling: {} zones, {} total nodes",
                            nodesByZone.size(),
                            nodesByZone.values().stream().mapToInt(List::size).sum());

                    // 并行求解每个 Zone
                    List<CompletableFuture<ZoneSolution>> futures = nodesByZone.entrySet().stream()
                            .map(entry -> solveForZone(entry.getKey(), entry.getValue(), detachedJob))
                            .collect(Collectors.toList());

                    // Use Uni.createFrom().completionStage to avoid blocking the event loop
                    return Uni.createFrom().completionStage(
                            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                                    .thenApply(v -> selectBestSolution(futures, detachedJob)));
                });
    }

    /**
     * 响应式加载节点并按 Zone 分组
     */
    private Uni<Map<String, List<Node>>> loadNodesByZone() {
        return Panache.withSession(() -> Satellite.findActive(LocalDateTime.now().minusMinutes(2))
                .map(satellites -> satellites.stream()
                        .map(nodeSpecsProvider::getNodeSpecs)
                        .collect(Collectors.groupingBy(
                                node -> Optional.ofNullable(node.getZoneId()).orElse("default")))));
    }

    /**
     * 在指定 Zone 中求解
     */
    private CompletableFuture<ZoneSolution> solveForZone(String zoneId, List<Node> nodes, Job job) {
        return CompletableFuture.supplyAsync(() -> {
            log.debug("🔧 Solving for zone '{}' with {} nodes", zoneId, nodes.size());

            LcmSolution solution = new LcmSolution();
            solution.setNodeList(new ArrayList<>(nodes));
            solution.setJobList(List.of(cloneJob(job)));

            UUID problemId = UUID.randomUUID();
            try {
                SolverJob<LcmSolution, UUID> solverJob = solverManager.solve(
                        problemId,
                        id -> solution,
                        s -> {
                        } // 不需要回调，我们会手动获取结果
                );

                LcmSolution finalSolution = solverJob.getFinalBestSolution();
                return new ZoneSolution(zoneId, finalSolution);

            } catch (Exception e) {
                log.error("Zone '{}' solving failed: {}", zoneId, e.getMessage());
                return new ZoneSolution(zoneId, solution); // 返回未求解的方案
            }
        }, zoneExecutor);
    }

    /**
     * 从所有 Zone 解中选择最优解
     */
    private Map<String, Node> selectBestSolution(List<CompletableFuture<ZoneSolution>> futures, Job originalJob) {
        return futures.stream()
                .map(CompletableFuture::join)
                .filter(zs -> zs.solution.getJobList().get(0).getAssignedNode() != null)
                .max(Comparator.comparing(zs -> {
                    HardSoftScore score = (HardSoftScore) zs.solution().getScore();
                    if (score == null) {
                        return HardSoftScore.of(Integer.MIN_VALUE, Integer.MIN_VALUE);
                    }
                    return score;
                }))
                .map(bestZone -> {
                    Node assignedNode = bestZone.solution.getJobList().get(0).getAssignedNode();
                    log.info("🎉 Job {} assigned to Node {} in Zone '{}'",
                            originalJob.getId(), assignedNode.getId(), bestZone.zoneId);

                    Job dispatchJob = cloneJob(originalJob);
                    dispatchJob.setAssignedNode(assignedNode);
                    dispatchJob.setAssignedNodeId(assignedNode.getId());
                    vertx.getDelegate().runOnContext(ignored -> jobEmitter.send(dispatchJob));

                    return Map.of(originalJob.getId(), assignedNode);
                })
                .orElseGet(() -> {
                    log.warn("⚠️ Job {} could not be assigned in any zone!", originalJob.getId());
                    return Collections.emptyMap();
                });
    }

    /**
     * 克隆 Job（每个 Zone 需要独立的 Job 实例）
     */
    private Job cloneJob(Job original) {
        Job clone = new Job();
        clone.setId(original.getId());
        clone.setRequiredCpuCores(original.getRequiredCpuCores());
        clone.setRequiredMemoryGb(original.getRequiredMemoryGb());
        clone.setRequiredGpuCount(original.getRequiredGpuCount());
        clone.setRequiredGpuModel(original.getRequiredGpuModel());
        clone.setRequiresNvlink(original.isRequiresNvlink());
        clone.setMinNvlinkBandwidthGbps(original.getMinNvlinkBandwidthGbps());
        return clone;
    }

    /**
     * Zone 求解结果包装类
     */
    private record ZoneSolution(String zoneId, LcmSolution solution) {
    }
}
