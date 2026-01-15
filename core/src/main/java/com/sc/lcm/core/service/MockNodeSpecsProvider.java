package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Node;
import com.sc.lcm.core.domain.Satellite;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

/**
 * Mock 节点规格提供者
 * 用于开发和测试阶段，返回模拟的硬件规格
 * 
 * 生产环境应替换为 RedfishNodeSpecsProvider 或 DiscoveryNodeSpecsProvider
 */
@ApplicationScoped
@Slf4j
public class MockNodeSpecsProvider implements NodeSpecsProvider {

    // 可配置的默认规格
    private static final int DEFAULT_CPU_CORES = 32;
    private static final long DEFAULT_MEMORY_GB = 128;
    private static final int DEFAULT_GPU_COUNT = 0;
    private static final String DEFAULT_GPU_MODEL = "A100";

    @Override
    public Node getNodeSpecs(Satellite satellite) {
        log.debug("📊 Using mock specs for satellite: {}", satellite.getHostname());

        // 根据主机名推断 GPU 配置（简单的启发式规则）
        int gpuCount = DEFAULT_GPU_COUNT;
        if (satellite.getHostname() != null) {
            String hostname = satellite.getHostname().toLowerCase();
            if (hostname.contains("gpu") || hostname.contains("cuda") || hostname.contains("nvidia")) {
                gpuCount = 4; // 假设 GPU 节点有 4 个 GPU
            }
        }

        return new Node(
                satellite.getId(),
                DEFAULT_CPU_CORES,
                DEFAULT_MEMORY_GB,
                gpuCount,
                gpuCount > 0 ? DEFAULT_GPU_MODEL : null);
    }
}
