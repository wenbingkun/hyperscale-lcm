# 资源调度与编排架构设计方案 (Resource Scheduling & Orchestration Design)

## 1. 需求分析 (Requirements Analysis)
用户希望在基础的 BMC 管理之上，实现类似 "资源池" 与 "作业调度器" 的功能：
*   **资源发现**: 自动识别服务器的深层硬件信息（GPU型号/显存、CPU核数/架构、内存大小）。
*   **约束求解**: 能够处理复杂的分配请求，例如 "需要30台配备 NVIDIA A100 的服务器，且尽可能位于同一个机架以减少网络延迟"。
*   **任务编排**: 在选定的资源上执行任务。

这实质上是将 `sc-scm` 升级为一个轻量级的 **HPC/AI 集群调度器**。

---

## 2. 总体架构设计 (Architecture Design)

建议采用 **Control Plane** (控制面) 与 **Compute Plane** (计算面) 分离的架构。

*   **控制面 (Quarkus + Timefold)**: 负责决策。
*   **计算面 (Redfish + Agent)**: 负责执行。

### 架构图
```mermaid
graph TD
    User[用户/上层应用] -->|提交作业请求| API_Gateway
    API_Gateway -->|REST/gRPC| Scheduler_Service[调度服务 (Quarkus + Timefold)]
    
    subgraph Core_Services
        Scheduler_Service <-->|读取资源状态| Inventory_DB[(资源数据库)]
        Inventory_DB <-->|同步| Discovery_Service[发现服务]
    end
    
    subgraph Infrastructure
        Discovery_Service -->|Redfish/IPMI| BMC[物理机BMC]
        Scheduler_Service -->|分配指令| Execution_Engine[执行引擎]
        Execution_Engine -->|SSH/Agent| OS[宿主操作系统]
    end
```

---

## 3. 核心模块详细设计 (Detailed Design)

### 3.1 资源发现模块 (Resource Discovery)
**挑战**: 标准 Redfish 可能无法直接获取详细的 GPU 信息（如 CUDA Core 数、显存占用），除非厂商实现了 OEM 扩展。
**方案**:
1.  **带外 (OOB) 基础发现**: 使用 Redfish `PCIeDevice` 集合获取 GPU 型号。
2.  **带内 (In-Band) 深度发现**: 
    *   在服务器 OS 中运行轻量级 Agent (如 Prometheus Node Exporter + DCGM Exporter)。
    *   Agent 上报详细指标到 Prometheus/PushGateway。
    *   调度器订阅这些实时指标。

### 3.2 智能调度模块 (Intelligent Scheduler)
这是核心部分，建议使用 **Timefold (前 OptaPlanner)** 运行在 **Quarkus** 上。

**领域模型 (Domain Model)**:
*   `Node`: 代表物理机，包含属性 `cpuCores`, `gpuModel`, `gpuCount`, `memory`, `networkZone`。
*   `Job`: 代表任务，包含属性 `requiredGpuCount`, `requiredCpu`, `priority`。
*   `Allocation`: 规划变量，建立 `Job` 到 `List<Node>` 的映射。

**约束 (Constraints)**:
*   **硬约束 (Hard Constraints)**: 
    *   节点必须满足 Job 的最小硬件要求（GPU型号必须匹配）。
    *   节点不能被重复分配给互斥的任务。
    *   节点必须处于 "Health OK" 状态。
*   **软约束 (Soft Constraints)**:
    *   **资源碎片最小化**: 优先填满碎片资源少的节点。
    *   **能耗优化**: 优先使用 PUE 值更低的机柜中的节点。
    *   **网络亲和性**: 分配给同一个 Job 的节点应尽可能在同一个交换机/机架下。

**技术选型**:
*   `quarkus-timefold-solver`: 提供了开箱即用的 Quarkus 集成。

### 3.3 执行引擎 (Execution Engine)
完成资源分配后，如何使用这些资源？
*   **方案 A (SSH Push)**: 适用于简单的一次性脚本任务。系统通过 Ansible/SSH 批量连接到选中的 30 台机器执行命令。
*   **方案 B (Job Scheduler Integration)**: 适用于专业 HPC 场景。SCM 仅作为 "资源管理器"，根据调度结果自动生成 **Slurm** 或 **Check** 的配置文件 (`slurm.conf`)，或者自动给 **Kubernetes** 节点打 Label (`kubectl label node ...`)，让 K8s 调度 Pod 过去。

---

## 4. 技术实现难点 (Challenges)

1.  **GPU 拓扑感知**:
    *   高端 AI 训练需要关注 GPU 之间的互联带宽 (NVLink vs PCIe)。这需要从 OS 层 (运行 `nvidia-smi topo -m`) 获取数据并建模。
2.  **资源锁定与并发**:
    *   当调度器在计算 "最佳 30 台机器" 时（这是一个耗时计算），这些机器的状态可能发生变化（例如突然宕机）。需要实现 **乐观锁** 或 **预留机制**。
3.  **异构资源标准化**:
    *   如何统一描述 "NVIDIA A100" 和 "AMD MI250"？需要设计一套抽象的资源描述语言 (RDL)。

---

## 5. 示例代码片段 (Quarkus + Timefold)

```java
// domain/Server.java
@PlanningEntity
public class Server {
    private String id;
    private int gpuCount;
    private String gpuModel;
    
    // 规划变量：该服务器当前分配给了哪个任务
    @PlanningVariable(valueRangeProviderRefs = "jobRange")
    private Job allocatedJob;
}

// solver/ResourceConstraintProvider.java
public class ResourceConstraintProvider implements ConstraintProvider {
    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
            gpuCapacityConflict(factory),
            networkLocality(factory)
        };
    }

    // 硬约束：GPU数量不足
    private Constraint gpuCapacityConflict(ConstraintFactory factory) {
        return factory.from(Server.class)
                .join(Job.class, Joiners.equal(Server::getAllocatedJob, Function.identity()))
                .filter((server, job) -> server.getGpuCount() < job.getRequiredGpuPerNode())
                .penalize("GPU Capacity Insufficient", HardSoftScore.ONE_HARD);
    }
}
```

## 6. 总结

引入 **Quarkus + Timefold** 是实现您所描述的 "动态可调配资源" 的最佳技术路线。它将系统从简单的 "设备管理" 提升到了 "智能决策" 的层次，能够处理仅靠数据库查询无法解决的复杂组合优化问题。
