package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Node;
import com.sc.lcm.core.domain.Satellite;

/**
 * 节点规格提供者接口
 * 用于从 Satellite 获取/推断硬件规格信息
 * 支持切换不同的实现（Mock、Redfish、Discovery）
 */
public interface NodeSpecsProvider {

    /**
     * 根据 Satellite 信息获取对应的 Node 规格
     * 
     * @param satellite 已注册的 Satellite
     * @return 包含硬件规格的 Node 对象
     */
    Node getNodeSpecs(Satellite satellite);
}
