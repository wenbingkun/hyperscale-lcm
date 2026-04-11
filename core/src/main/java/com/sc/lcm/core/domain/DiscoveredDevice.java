package com.sc.lcm.core.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

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

    /** 厂商提示信息，可来自 OUI、CMDB 或人工补录 */
    private String manufacturerHint;

    /** 型号提示信息，可来自探测或人工补录 */
    private String modelHint;

    /** 推荐的 Redfish 模板名称 */
    private String recommendedRedfishTemplate;

    /** 单设备 Redfish 认证模式例外覆盖 */
    private String redfishAuthModeOverride;

    /** 首次认证状态 */
    @Enumerated(EnumType.STRING)
    private AuthStatus authStatus = AuthStatus.PENDING;

    /** 零接触纳管 claim 状态 */
    @Enumerated(EnumType.STRING)
    private ClaimStatus claimStatus = ClaimStatus.DISCOVERED;

    /** 匹配到的凭据档案 ID */
    private String credentialProfileId;

    /** 匹配到的凭据档案名称 */
    private String credentialProfileName;

    /** 凭据来源（例如 PROFILE / MANUAL） */
    private String credentialSource;

    /** 最近一次 claim / 认证规划说明 */
    @Column(columnDefinition = "TEXT")
    private String claimMessage;

    /** 最近一次认证尝试时间 */
    private LocalDateTime lastAuthAttemptAt;

    /** 最近一次成功的 Redfish 认证模式 */
    private String lastSuccessfulAuthMode;

    /** 最近一次认证失败码（例如 HTTP_401 / SESSION_TOKEN_MISSING） */
    private String lastAuthFailureCode;

    /** 最近一次认证失败原因 */
    @Column(columnDefinition = "TEXT")
    private String lastAuthFailureReason;

    /** BMC capability 快照原始 JSON */
    @JsonIgnore
    @Column(name = "bmc_capabilities", columnDefinition = "TEXT")
    private String bmcCapabilitiesJson;

    /** 最近一次 capability 探测时间 */
    private LocalDateTime lastCapabilityProbeAt;

    /** 最近一次托管账号密码轮换时间 */
    private LocalDateTime lastRotationAt;

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

    public enum AuthStatus {
        PENDING, // 尚未做认证规划
        PROFILE_MATCHED, // 已匹配自动化凭据策略
        AUTH_PENDING, // 检测到 BMC 但缺少凭据
        AUTH_FAILED, // 已尝试认证但失败
        AUTHENTICATED // 已完成首次认证/接管
    }

    public enum ClaimStatus {
        DISCOVERED, // 仅发现
        READY_TO_CLAIM, // 已具备自动纳管条件
        CLAIMING, // 正在执行首次接管
        CLAIMED, // 已完成首次接管
        MANAGED // 已进入受管态
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

    @Transient
    public Map<String, Object> getBmcCapabilities() {
        if (bmcCapabilitiesJson == null || bmcCapabilitiesJson.isBlank()) {
            return null;
        }
        try {
            return JSON_MAPPER.readValue(bmcCapabilitiesJson, MAP_TYPE);
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("raw", bmcCapabilitiesJson);
            fallback.put("parseError", e.getMessage());
            return fallback;
        }
    }

    public void setBmcCapabilities(Map<String, Object> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            this.bmcCapabilitiesJson = null;
            return;
        }
        try {
            this.bmcCapabilitiesJson = JSON_MAPPER.writeValueAsString(capabilities);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize BMC capabilities", e);
        }
    }
}
