package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Job.JobStatus;
import com.sc.lcm.core.domain.Node;
import java.util.Collection;
import java.util.List;

/**
 * Hard feasibility of placing one job on one node given what is already placed there.
 * Occupancy is derived from job rows (no separate ledger), so completion, cancellation, resolve and Core restarts
 * release or restore it automatically. UNKNOWN keeps occupying: the command may still be running.
 */
public final class CapacityCheck {

    /** Statuses that hold node capacity. */
    public static final List<JobStatus> OCCUPYING = List.of(JobStatus.SCHEDULED, JobStatus.RUNNING, JobStatus.UNKNOWN);

    public record Usage(int cpu, long memoryGb, int gpu) {
        public static final Usage NONE = new Usage(0, 0, 0);

        public static Usage of(Collection<Job> jobs) {
            int cpu = 0;
            long mem = 0;
            int gpu = 0;
            for (Job job : jobs) {
                cpu += job.getRequiredCpuCores();
                mem += job.getRequiredMemoryGb();
                gpu += job.getRequiredGpuCount();
            }
            return new Usage(cpu, mem, gpu);
        }
    }

    private CapacityCheck() {
    }

    /** @return null when the job fits, otherwise a human-readable reason. */
    public static String mismatch(Node node, Usage used, Job job) {
        if (used.cpu() + job.getRequiredCpuCores() > node.getCpuCores()) {
            return String.format("insufficient CPU on %s: need %d, free %d", node.getId(),
                    job.getRequiredCpuCores(), Math.max(0, node.getCpuCores() - used.cpu()));
        }
        if (used.memoryGb() + job.getRequiredMemoryGb() > node.getMemoryGb()) {
            return String.format("insufficient memory on %s: need %dGB, free %dGB", node.getId(),
                    job.getRequiredMemoryGb(), Math.max(0, node.getMemoryGb() - used.memoryGb()));
        }
        if (used.gpu() + job.getRequiredGpuCount() > node.getGpuCount()) {
            return String.format("insufficient GPUs on %s: need %d, free %d", node.getId(),
                    job.getRequiredGpuCount(), Math.max(0, node.getGpuCount() - used.gpu()));
        }
        String model = job.getRequiredGpuModel();
        if (model != null && !model.isBlank() && !model.equalsIgnoreCase(node.getGpuModel())) {
            return String.format("GPU model %s is not available on %s", model, node.getId());
        }
        if (job.isRequiresNvlink() && !node.hasNvlink()) {
            return String.format("%s has no NVLink/NVSwitch topology", node.getId());
        }
        if (job.getMinNvlinkBandwidthGbps() > node.getNvlinkBandwidthGbps()) {
            return String.format("%s NVLink bandwidth %d Gbps is below the required %d Gbps", node.getId(),
                    node.getNvlinkBandwidthGbps(), job.getMinNvlinkBandwidthGbps());
        }
        return null;
    }
}
