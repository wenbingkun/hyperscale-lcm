package com.sc.lcm.core.domain;

import java.time.LocalDateTime;

/**
 * 作业状态回调消息 (P6-3)
 * 
 * Satellite 执行完成后发送
 */
public record JobStatusCallback(
        String jobId,
        String nodeId,
        String status, // RUNNING, COMPLETED, FAILED
        int exitCode,
        String errorMessage,
        String stdout,
        String stderr,
        long executionTimeMs,
        LocalDateTime completedAt) {

    /**
     * 创建成功回调
     */
    public static JobStatusCallback success(String jobId, String nodeId,
            long executionTimeMs, String stdout) {
        return new JobStatusCallback(
                jobId, nodeId, "COMPLETED", 0, null,
                stdout, null, executionTimeMs, LocalDateTime.now());
    }

    /**
     * 创建失败回调
     */
    public static JobStatusCallback failure(String jobId, String nodeId,
            int exitCode, String errorMessage,
            long executionTimeMs, String stderr) {
        return new JobStatusCallback(
                jobId, nodeId, "FAILED", exitCode, errorMessage,
                null, stderr, executionTimeMs, LocalDateTime.now());
    }
}
