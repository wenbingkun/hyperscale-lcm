package com.sc.lcm.core.solver;

import static ai.timefold.solver.core.api.score.stream.ConstraintCollectors.sum;
import static ai.timefold.solver.core.api.score.stream.ConstraintCollectors.sumLong;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import com.sc.lcm.core.domain.Job;

/**
 * Timefold 调度约束 - 包含 GPU 拓扑约束
 */
public class LcmConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
                // 硬约束 - 资源容量
                cpuCapacity(factory),
                memoryCapacity(factory),
                gpuCountCapacity(factory),

                // 硬约束 - GPU 拓扑 (P2-3)
                nvlinkRequired(factory),
                nvlinkBandwidthRequired(factory),

                // 软约束 - 优化
                preferHigherNvlinkBandwidth(factory)
        };
    }

    // ============== 硬约束: 资源容量 ==============

    public Constraint cpuCapacity(ConstraintFactory factory) {
        return factory.forEach(Job.class)
                .groupBy(Job::getAssignedNode, sum(Job::getRequiredCpuCores))
                .filter((node, requiredCpu) -> requiredCpu > node.getCpuCores())
                .penalize(HardSoftScore.ONE_HARD, (node, requiredCpu) -> requiredCpu - node.getCpuCores())
                .asConstraint("CPU Capacity");
    }

    public Constraint memoryCapacity(ConstraintFactory factory) {
        return factory.forEach(Job.class)
                .groupBy(Job::getAssignedNode, sumLong(Job::getRequiredMemoryGb))
                .filter((node, requiredMem) -> requiredMem > node.getMemoryGb())
                .penalize(HardSoftScore.ONE_HARD, (node, requiredMem) -> (int) (requiredMem - node.getMemoryGb()))
                .asConstraint("Memory Capacity");
    }

    public Constraint gpuCountCapacity(ConstraintFactory factory) {
        return factory.forEach(Job.class)
                .groupBy(Job::getAssignedNode, sum(Job::getRequiredGpuCount))
                .filter((node, requiredGpu) -> requiredGpu > node.getGpuCount())
                .penalize(HardSoftScore.ONE_HARD, (node, requiredGpu) -> requiredGpu - node.getGpuCount())
                .asConstraint("GPU Count Capacity");
    }

    // ============== 硬约束: GPU 拓扑 (P2-3) ==============

    /**
     * 硬约束: 如果作业需要 NVLink，节点必须有 NVLink/NVSwitch
     */
    public Constraint nvlinkRequired(ConstraintFactory factory) {
        return factory.forEach(Job.class)
                .filter(Job::isRequiresNvlink)
                .filter(job -> job.getAssignedNode() != null)
                .filter(job -> !job.getAssignedNode().hasNvlink())
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("NVLink Required");
    }

    /**
     * 硬约束: 如果作业有最小 NVLink 带宽需求，节点必须满足
     */
    public Constraint nvlinkBandwidthRequired(ConstraintFactory factory) {
        return factory.forEach(Job.class)
                .filter(job -> job.getMinNvlinkBandwidthGbps() > 0)
                .filter(job -> job.getAssignedNode() != null)
                .filter(job -> job.getAssignedNode().getNvlinkBandwidthGbps() < job.getMinNvlinkBandwidthGbps())
                .penalize(HardSoftScore.ONE_HARD,
                        job -> job.getMinNvlinkBandwidthGbps() - job.getAssignedNode().getNvlinkBandwidthGbps())
                .asConstraint("NVLink Bandwidth Required");
    }

    // ============== 软约束: 优化 ==============

    /**
     * 软约束: 优先分配到 NVLink 带宽更高的节点
     */
    public Constraint preferHigherNvlinkBandwidth(ConstraintFactory factory) {
        return factory.forEach(Job.class)
                .filter(Job::isRequiresNvlink)
                .filter(job -> job.getAssignedNode() != null)
                .filter(job -> job.getAssignedNode().hasNvlink())
                .reward(HardSoftScore.ONE_SOFT,
                        job -> job.getAssignedNode().getNvlinkBandwidthGbps() / 100) // 每 100 GB/s 加 1 分
                .asConstraint("Prefer Higher NVLink Bandwidth");
    }
}
