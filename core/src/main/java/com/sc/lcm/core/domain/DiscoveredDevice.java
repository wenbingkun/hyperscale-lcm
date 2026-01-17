package com.sc.lcm.core.domain;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 发现的设备 - 待纳管设备池
 * 
 * 工作流程:
 * 1. 网络扫描发现新设备 -> 创建 DiscoveredDevice
 * 2. 管理员审批 -> 状态变为 APPROVED
 * 3. 系统自动纳管 -> 创建 Satellite 并删除本记录
 */
@Entity
@Table(name = "discovered_devices")
@Getter
@Setter
@NoArgsConstructor
public class DiscoveredDevice extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** 设备 IP 地址 */
    @Column(nullable = false)
    private String ipAddress;

    /** MAC 地址 */
    private String macAddress;

    /** 主机名 (通过 DNS 反向解析) */
    private String hostname;

    /** 发现方式: SCAN, DHCP, MANUAL */
    @Enumerated(EnumType.STRING)
    private DiscoveryMethod discoveryMethod;

    /** 设备状态 */
    @Enumerated(EnumType.STRING)
    private DiscoveryStatus status = DiscoveryStatus.PENDING;

    /** 发现时间 */
    private LocalDateTime discoveredAt = LocalDateTime.now();

    /** 最后探测时间 */
    private LocalDateTime lastProbedAt;

    /** 设备类型推断: GPU_NODE, COMPUTE_NODE, STORAGE, NETWORK, UNKNOWN */
    private String inferredType;

    /** 开放端口 (JSON 数组格式) */
    @Column(columnDefinition = "TEXT")
    private String openPorts;

    /** BMC/IPMI 地址 (如果有) */
    private String bmcAddress;

    /** 扫描备注 */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /** 所属租户 */
    private String tenantId;

    public enum DiscoveryMethod {
        SCAN, // 网络扫描发现
        DHCP, // DHCP 监听发现
        MANUAL, // 手动添加
        AGENT // Agent 主动注册
    }

    public enum DiscoveryStatus {
        PENDING, // 待审批
        APPROVED, // 已批准，等待纳管
        REJECTED, // 已拒绝
        MANAGED, // 已纳管
        OFFLINE // 设备离线
    }

    // ============== 查询方法 ==============

    public static Uni<List<DiscoveredDevice>> findByStatus(DiscoveryStatus status) {
        return list("status", status);
    }

    public static Uni<List<DiscoveredDevice>> findPending() {
        return list("status", DiscoveryStatus.PENDING);
    }

    public static Uni<DiscoveredDevice> findByIp(String ipAddress) {
        return find("ipAddress", ipAddress).firstResult();
    }

    public static Uni<List<DiscoveredDevice>> findByTenant(String tenantId) {
        return list("tenantId", tenantId);
    }

    public static Uni<Long> countPending() {
        return count("status", DiscoveryStatus.PENDING);
    }
}
