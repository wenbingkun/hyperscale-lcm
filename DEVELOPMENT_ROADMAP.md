# Hyperscale LCM 开发规划路线图 (Development Roadmap)

本路线图旨在将 `hyperscale-lcm` 从原型构建为可管理数万台服务器的企业级平台。

## 📅 阶段一：地基与连接 (Foundation & Connectivity) [当前阶段]
**目标**: 打通 Core 与 Satellite 的通信，实现基础资产数据上报。
*   **Core (Quarkus)**:
    *   [ ] 设计并实现领域模型：`Server`, `NetworkInterface`, `GpuDevice`。
    *   [ ] 集成 gRPC 服务端，定义 `.proto` 协议。
    *   [ ] 实现资产注册 API。
*   **Satellite (Go)**:
    *   [ ] 实现 gRPC 客户端，完成与 Core 的握手。
    *   [ ] 实现基础的数据采集（Mock数据或读取本地 OS 信息）。
    *   [ ] 实现心跳机制 (Heartbeat)。

## 📅 阶段二：主动发现与深度采集 (Active Discovery & Deep Inspection)
**目标**: 实现“零接触”纳管，自动发现网络中的新设备并获取详细硬件信息。
*   **Satellite**:
    *   [ ] 集成 `masscan` 或实现 ARP/ICMP 扫描器。
    *   [ ] 实现 DHCP Listener，捕获新设备上线。
    *   [ ] 实现 Redfish Client，远程获取 BMC 信息（电源、温度）。
*   **Core**:
    *   [ ] 实现“待纳管设备池” (Discovered Pool)。
    *   [ ] 纳管策略引擎（自动/手动批准）。

## 📅 阶段三：智能调度与资源池化 (Intelligent Scheduling)
**目标**: 实现基于 Timefold 的 AI 算力调度。
*   **Core**:
    *   [ ] 完善 GPU 拓扑模型 (NVLink, NVSwitch 建模)。
    *   [ ] 配置 Timefold 约束：
        *   硬约束：GPU 型号匹配、显存大小、网络区域。
        *   软约束：能耗最低、碎片最小化。
    *   [ ] 开发调度 API：`POST /api/allocation/request`。

## 📅 📅 阶段四：执行与交付 (Execution & Delivery)
**目标**: 闭环“分配-执行”流程。
*   **Execution**:
    *   [ ] 集成 Ansible 或 SSH 库，实现对被选定机器的命令下发。
    *   [ ] (高级) 集成 PXE/iPXE，实现裸金属 OS 自动化重装。
*   **Frontend**:
    *   [ ] 开发大屏监控看板 (Vue/React)。

---

## 🎯 即将开始的开发任务 (Next Sprint)
重点攻克 **阶段一**：
1.  定义 `lcm.proto` 通信协议。
2.  实现 Satellite 向 Core 注册的流程。
3.  Core 端将注册上来的卫星节点持久化到 PostgreSQL。
