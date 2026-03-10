package com.sc.lcm.core.service;

import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.api.solver.SolverJob;
import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.LcmSolution;
import com.sc.lcm.core.domain.Node;
import com.sc.lcm.core.domain.Satellite;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

/**
 * 调度服务 - 使用响应式数据库查询 + Timefold 求解
 */
@ApplicationScoped
@Slf4j
public class SchedulingService {

    @Inject
    SolverManager<LcmSolution, UUID> solverManager;

    @Inject
    NodeSpecsProvider nodeSpecsProvider;

    @Inject
    @Channel("job-queue-out")
    Emitter<Job> jobEmitter;

    /**
     * 响应式加载问题数据
     */
    @WithSession
    public Uni<LcmSolution> loadProblemReactive(Job newJob) {
        return Satellite.findActive(java.time.LocalDateTime.now().minusMinutes(2))
                .map(satellites -> {
                    log.info("📊 Scheduler found {} active satellites", satellites.size());

                    LcmSolution solution = new LcmSolution();

                    List<Node> nodes = satellites.stream()
                            .map(nodeSpecsProvider::getNodeSpecs)
                            .collect(Collectors.toList());
                    solution.setNodeList(nodes);

                    List<Job> jobs = new ArrayList<>();
                    jobs.add(copyJob(newJob));
                    solution.setJobList(jobs);

                    return solution;
                });
    }

    public void saveSolution(LcmSolution solution) {
        Job job = solution.getJobList().get(0);
        if (job.getAssignedNode() != null) {
            String nodeId = job.getAssignedNode().getId();
            job.setAssignedNodeId(nodeId);
            log.info("Job {} assigned to Node {} (Queuing to Kafka)", job.getId(), nodeId);
            jobEmitter.send(copyJob(job));
        } else {
            log.warn("Job {} could not be assigned!", job.getId());
        }
    }

    /**
     * 调度作业 - 真正非阻塞模式
     * 开启 Timefold 异步求解并立即返回
     */
    public Uni<Void> scheduleJob(Job job) {
        return loadProblemReactive(job)
                .invoke(solution -> {
                    UUID problemId = UUID.randomUUID();
                    log.info("🚀 Starting asynchronous solving for Job {}", job.getId());

                    // 异步启动求解，结果将通过 this::saveSolution 回调
                    solverManager.solve(
                            problemId,
                            id -> solution,
                            this::saveSolution);
                })
                .replaceWithVoid();
    }

    private Job copyJob(Job original) {
        Job copy = new Job();
        copy.setId(original.getId());
        copy.setName(original.getName());
        copy.setDescription(original.getDescription());
        copy.setRequiredCpuCores(original.getRequiredCpuCores());
        copy.setRequiredMemoryGb(original.getRequiredMemoryGb());
        copy.setRequiredGpuCount(original.getRequiredGpuCount());
        copy.setRequiredGpuModel(original.getRequiredGpuModel());
        copy.setRequiresNvlink(original.isRequiresNvlink());
        copy.setMinNvlinkBandwidthGbps(original.getMinNvlinkBandwidthGbps());
        copy.setStatus(original.getStatus());
        copy.setAssignedNodeId(original.getAssignedNodeId());
        copy.setAssignedNode(original.getAssignedNode());
        copy.setTenantId(original.getTenantId());
        copy.setPriority(original.getPriority());
        copy.setPreemptible(original.isPreemptible());
        copy.setNodeSelector(original.getNodeSelector());
        return copy;
    }
}
