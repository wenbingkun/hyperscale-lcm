package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Node;
import com.sc.lcm.core.domain.Satellite;
import com.sc.lcm.core.grpc.HardwareSpecs;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于注册信息的节点规格提供者
 * 
 * 从 Satellite 注册时上报的 HardwareSpecs 创建/更新 Node 实体，
 * 替代 MockNodeSpecsProvider 提供真实硬件数据。
 */
@ApplicationScoped
@Slf4j
public class RegistrationNodeSpecsProvider implements NodeSpecsProvider {

    // 内存缓存注册的硬件规格，用于同步访问
    private final ConcurrentHashMap<String, HardwareSpecs> specsCache = new ConcurrentHashMap<>();

    /**
     * 缓存 Satellite 注册时上报的硬件规格
     */
    public void cacheHardwareSpecs(String satelliteId, HardwareSpecs specs) {
        if (specs != null) {
            specsCache.put(satelliteId, specs);
            log.info("📊 Cached hardware specs for {}: CPU={}, RAM={}GB, GPU={}x{}",
                    satelliteId, specs.getCpuCores(), specs.getMemoryGb(),
                    specs.getGpuCount(), specs.getGpuModel());
        }
    }

    /**
     * 根据缓存的硬件规格创建或返回 Node
     */
    @Override
    public Node getNodeSpecs(Satellite satellite) {
        HardwareSpecs specs = specsCache.get(satellite.getId());

        if (specs != null) {
            Node node = new Node(
                    satellite.getId(),
                    specs.getCpuCores(),
                    specs.getMemoryGb(),
                    specs.getGpuCount(),
                    specs.getGpuModel());
            node.setGpuTopology(specs.getGpuTopology());
            node.setNvlinkBandwidthGbps(specs.getNvlinkBandwidthGbps());
            node.setRackId(specs.getRackId());
            node.setZoneId(specs.getZoneId());
            node.setBmcIp(specs.getBmcIp());
            node.setBmcMac(specs.getBmcMac());
            node.setSystemSerial(specs.getSystemSerial());
            node.setSystemModel(specs.getSystemModel());
            return node;
        }

        // 降级: 如果没有缓存的规格，返回基础信息
        log.debug("No hardware specs cached for satellite {}, using defaults", satellite.getId());
        return new Node(satellite.getId(), 1, 0, 0, null);
    }

    /**
     * 响应式地持久化 Node 实体到数据库
     */
    public Uni<Void> persistNode(String satelliteId, HardwareSpecs specs) {
        if (specs == null) {
            return Uni.createFrom().voidItem();
        }

        return Panache.withTransaction(() -> persistNodeInCurrentTransaction(satelliteId, specs));
    }

    public Uni<Void> persistNodeInCurrentTransaction(String satelliteId, HardwareSpecs specs) {
        if (specs == null) {
            return Uni.createFrom().voidItem();
        }

        return Node.<Node>findById(satelliteId)
                .onItem().transformToUni(existing -> {
                    if (existing != null) {
                        // 更新已有节点
                        existing.setCpuCores(specs.getCpuCores());
                        existing.setMemoryGb(specs.getMemoryGb());
                        existing.setGpuCount(specs.getGpuCount());
                        existing.setGpuModel(specs.getGpuModel());
                        existing.setGpuTopology(specs.getGpuTopology());
                        existing.setNvlinkBandwidthGbps(specs.getNvlinkBandwidthGbps());
                        existing.setRackId(specs.getRackId());
                        existing.setZoneId(specs.getZoneId());
                        existing.setBmcIp(specs.getBmcIp());
                        existing.setBmcMac(specs.getBmcMac());
                        existing.setSystemSerial(specs.getSystemSerial());
                        existing.setSystemModel(specs.getSystemModel());
                        log.info("🔄 Updated Node {} specs (Model: {}, Serial: {})", satelliteId,
                                specs.getSystemModel(), specs.getSystemSerial());
                        return Uni.createFrom().voidItem();
                    } else {
                        // 创建新节点
                        Node node = new Node(
                                satelliteId,
                                specs.getCpuCores(),
                                specs.getMemoryGb(),
                                specs.getGpuCount(),
                                specs.getGpuModel());
                        node.setGpuTopology(specs.getGpuTopology());
                        node.setNvlinkBandwidthGbps(specs.getNvlinkBandwidthGbps());
                        node.setRackId(specs.getRackId());
                        node.setZoneId(specs.getZoneId());
                        node.setBmcIp(specs.getBmcIp());
                        node.setBmcMac(specs.getBmcMac());
                        node.setSystemSerial(specs.getSystemSerial());
                        node.setSystemModel(specs.getSystemModel());
                        log.info("✅ Created Node {} (CPU={}, GPU={}x{}, Model={})",
                                satelliteId, specs.getCpuCores(),
                                specs.getGpuCount(), specs.getGpuModel(), specs.getSystemModel());
                        return node.persist().replaceWithVoid();
                    }
                });
    }
}
