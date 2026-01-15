package com.sc.lcm.core.domain;

import java.util.Map;

/**
 * 作业执行消息 (P6-1)
 * 
 * 通过 Kafka 发送给 Satellite 执行
 */
public record JobExecutionMessage(
        String jobId,
        String nodeId,
        String dockerImage,
        String command,
        Map<String, String> environment,
        int cpuCores,
        long memoryGb,
        int gpuCount,
        String gpuModel,
        int priority,
        long timeoutSeconds) {

    /**
     * 从 Job 实体创建执行消息
     */
    public static JobExecutionMessage fromJob(Job job, String dockerImage, String command) {
        return new JobExecutionMessage(
                job.getId(),
                job.getAssignedNodeId(),
                dockerImage,
                command,
                Map.of(), // 环境变量
                job.getRequiredCpuCores(),
                job.getRequiredMemoryGb(),
                job.getRequiredGpuCount(),
                job.getRequiredGpuModel(),
                job.getPriority(),
                3600 // 默认 1 小时超时
        );
    }
}
