package com.sc.lcm.core.domain;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Satellite 实体 - 使用 Hibernate Reactive Panache
 * 所有数据库操作都是非阻塞的，返回 Uni/Multi
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class Satellite extends PanacheEntityBase {

    @Id
    private String id; // UUID assigned by Core

    private String clusterId; // ID of the multi-cluster region/data-center (default: "default")
    private String hostname;
    private String ipAddress;
    private String osVersion;
    private String agentVersion;

    private String status; // ONLINE, OFFLINE

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    // Heartbeat timestamp
    private LocalDateTime lastHeartbeat;

    public Satellite(String id, String clusterId, String hostname, String ipAddress, String osVersion,
            String agentVersion) {
        this.id = id;
        this.clusterId = (clusterId == null || clusterId.isBlank()) ? "default" : clusterId;
        this.hostname = hostname;
        this.ipAddress = ipAddress;
        this.osVersion = osVersion;
        this.agentVersion = agentVersion;
        this.status = "ONLINE";
    }

    // ============== 响应式查询方法 ==============

    /**
     * 响应式: 根据 ID 查找 Satellite
     */
    public static Uni<Satellite> findByIdReactive(String id) {
        return findById(id);
    }

    /**
     * 响应式: 查找活跃的 Satellite (心跳在指定时间内)
     */
    public static Uni<List<Satellite>> findActive(LocalDateTime since) {
        return list("lastHeartbeat > ?1", since);
    }

    /**
     * 响应式: 获取所有 Satellite
     */
    public static Uni<List<Satellite>> listAllReactive() {
        return listAll();
    }
}
