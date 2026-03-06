package com.sc.lcm.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WebSocket 事件协议模型
 * 
 * 统一的 JSON 消息格式:
 * {
 * "type": "EVENT_TYPE",
 * "payload": { ... },
 * "timestamp": 1709640000000
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsEvent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 事件类型枚举 */
    public enum EventType {
        CONNECTED, // 连接建立
        PONG, // 心跳响应
        STATUS, // 全局状态摘要
        NODE_STATUS, // 节点上下线
        HEARTBEAT_UPDATE, // 节点指标更新
        JOB_STATUS, // 作业状态变更
        SCHEDULE_EVENT, // 调度事件
        DISCOVERY_EVENT, // 设备发现事件
        ALERT // 告警
    }

    private EventType type;
    private Object payload;
    private long timestamp;

    /** 快捷构造 */
    public static WsEvent of(EventType type, Object payload) {
        return WsEvent.builder()
                .type(type)
                .payload(payload)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /** 序列化为 JSON 字符串 */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            return "{\"type\":\"ERROR\",\"payload\":{\"message\":\"Serialization failed\"}}";
        }
    }

    // ============== Payload DTOs ==============

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StatusPayload {
        private int onlineNodes;
        private int totalJobs;
        private int runningJobs;
        private int pendingJobs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class NodeStatusPayload {
        private String nodeId;
        private String hostname;
        private String status; // ONLINE / OFFLINE
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HeartbeatPayload {
        private String nodeId;
        private double cpuPercent;
        private double loadAvg;
        private long memoryUsedMb;
        private long memoryTotalMb;
        private int gpuCount;
        private double gpuAvgUtil;
        private String powerState;
        private int systemTemperatureCelsius;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JobStatusPayload {
        private String jobId;
        private String jobName;
        private String status;
        private String assignedNodeId;
        private Integer exitCode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SchedulePayload {
        private String jobId;
        private String nodeId;
        private String action; // ASSIGNED / PREEMPTED / COMPLETED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DiscoveryPayload {
        private String ipAddress;
        private String macAddress;
        private String discoveryMethod;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AlertPayload {
        private String severity; // INFO / WARNING / CRITICAL
        private String message;
        private String source;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConnectedPayload {
        private String message;
    }
}
