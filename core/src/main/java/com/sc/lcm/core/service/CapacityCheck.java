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

    public record Usage(long cpu, long memoryGb, long gpu) {
        public static final Usage NONE = new Usage(0, 0, 0);

        public static Usage of(Collection<Job> jobs) {
            long cpu = 0;
            long mem = 0;
            long gpu = 0;
            for (Job job : jobs) {
                cpu = addUsage(cpu, job.getRequiredCpuCores());
                mem = addUsage(mem, job.getRequiredMemoryGb());
                gpu = addUsage(gpu, job.getRequiredGpuCount());
            }
            return new Usage(cpu, mem, gpu);
        }
    }

    private CapacityCheck() {
    }

    // Legacy invalid rows cannot credit free capacity; large totals saturate instead of wrapping.
    private static long addUsage(long total, long value) {
        long nonnegative = Math.max(0L, value);
        return total > Long.MAX_VALUE - nonnegative ? Long.MAX_VALUE : total + nonnegative;
    }

    private static long free(long capacity, long used) {
        return Math.max(0L, Math.max(0L, capacity) - Math.max(0L, used));
    }

    public static String invalidRequest(int cpu, long memory, int gpu, int nvlinkBandwidth) {
        return cpu < 0 || memory < 0 || gpu < 0 || nvlinkBandwidth < 0
                ? "Resource requests must not be negative" : null;
    }

    /** @return null when the job fits, otherwise a human-readable reason. */
    public static String mismatch(Node node, Usage used, Job job) {
        String invalid = invalidRequest(job.getRequiredCpuCores(), job.getRequiredMemoryGb(),
                job.getRequiredGpuCount(), job.getMinNvlinkBandwidthGbps());
        if (invalid != null) {
            return invalid;
        }
        if (job.getRequiredCpuCores() > free(node.getCpuCores(), used.cpu())) {
            return String.format("insufficient CPU on %s: need %d, free %d", node.getId(),
                    job.getRequiredCpuCores(), free(node.getCpuCores(), used.cpu()));
        }
        if (job.getRequiredMemoryGb() > free(node.getMemoryGb(), used.memoryGb())) {
            return String.format("insufficient memory on %s: need %dGB, free %dGB", node.getId(),
                    job.getRequiredMemoryGb(), free(node.getMemoryGb(), used.memoryGb()));
        }
        if (job.getRequiredGpuCount() > free(node.getGpuCount(), used.gpu())) {
            return String.format("insufficient GPUs on %s: need %d, free %d", node.getId(),
                    job.getRequiredGpuCount(), free(node.getGpuCount(), used.gpu()));
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
