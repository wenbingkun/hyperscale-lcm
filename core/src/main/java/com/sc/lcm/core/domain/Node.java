package com.sc.lcm.core.domain;

import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 节点实体 - 支持 GPU 拓扑建模
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class Node {

    @PlanningId
    @Id
    private String id;

    // Hardware Specs
    private int cpuCores;
    private int gpuCount;
    private String gpuModel; // e.g., "A100", "H100"
    private long memoryGb;

    // Network Topology
    private String rackId;
    private String zoneId;

    // ============== GPU 拓扑 (P2-3) ==============

    /** GPU 互联拓扑类型: NVLink, NVSwitch, PCIe */
    private String gpuTopology;

    /** NVLink 带宽 (GB/s)，例如 A100 NVLink = 600 GB/s */
    private int nvlinkBandwidthGbps;

    /** Infiniband 交换机 ID，用于跨节点 GPU 通信优化 */
    private String ibFabricId;

    public Node(String id, int cpuCores, long memoryGb, int gpuCount, String gpuModel) {
        this.id = id;
        this.cpuCores = cpuCores;
        this.memoryGb = memoryGb;
        this.gpuCount = gpuCount;
        this.gpuModel = gpuModel;
    }

    // ============== 便捷方法 ==============

    /**
     * 检查节点是否有高速 GPU 互联 (NVLink 或 NVSwitch)
     */
    public boolean hasNvlink() {
        return "NVLink".equalsIgnoreCase(gpuTopology)
                || "NVSwitch".equalsIgnoreCase(gpuTopology);
    }

    /**
     * 检查节点是否在指定的 IB Fabric 中
     */
    public boolean isInIbFabric(String fabricId) {
        return fabricId != null && fabricId.equals(this.ibFabricId);
    }
}
