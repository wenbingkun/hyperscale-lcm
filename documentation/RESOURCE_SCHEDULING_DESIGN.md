# 资源调度与编排设计

> 更新日期: 2026-04-08
> 说明: 本文描述当前主干的实际调度链路，而不是早期概念草案。

## 1. 目标

调度模块需要在多集群、多机房、多拓扑约束下，为作业选择可执行节点，并把结果稳定地串到执行与状态回调链路中。

当前已落地的重点是：

- `clusterId` 级别隔离
- `zoneId` 级别分区并行求解
- GPU / NVLink / IB Fabric 拓扑感知
- 调度结果持久化后再派发执行
- WebSocket 和拓扑页实时反馈

## 2. 当前调度流程

```mermaid
graph LR
    Submit[Job / Allocation Request]
    Core[SchedulingService]
    Partition[PartitionedSchedulingService]
    Solver[Timefold Solver]
    Persist[Persist SCHEDULED Job]
    Dispatch[JobDispatcher]
    Satellite[Satellite Executor]
    Status[Status Callback]
    UI[WebSocket + UI]

    Submit --> Core
    Core --> Partition
    Partition --> Solver
    Solver --> Persist
    Persist --> Dispatch
    Dispatch --> Satellite
    Satellite --> Status
    Status --> UI
```

## 3. 领域模型

### 3.1 Node

当前调度相关字段包括：

- `cpuCores`
- `memoryGb`
- `gpuModel`
- `gpuCount`
- `zoneId`
- `rack`
- `ibFabricId`
- `gpuTopology`
- `nvlinkBandwidthGbps`
- `healthStatus`

### 3.2 Job

当前调度与执行链路会使用：

- `requiredCpuCores`
- `requiredMemoryGb`
- `requiredGpuCount`
- `requiredGpuModel`
- `clusterId`
- `priority`
- `executionType`
- `executionPayload`

### 3.3 输出结果

调度完成后会持久化：

- `status = SCHEDULED`
- `assignedNodeId`
- `scheduledAt`

随后再下发执行事件，避免前端和回调链路看到“已执行但未落库”的状态撕裂。

## 4. 约束与求解策略

### 4.1 硬约束

- 节点必须在目标 `clusterId` 内且处于活跃状态
- 节点必须满足 CPU、内存、GPU 数量和 GPU 型号需求
- 节点必须可用于当前作业，不得与互斥分配冲突

### 4.2 软约束

- 优先选择同一 `zoneId` 内的资源，减少跨区通信
- 优先选择更优的 GPU / NVLink / IB Fabric 拓扑
- 优先减少碎片化分配

### 4.3 分区并行

当节点分布在多个 Zone 时：

1. Core 先按 `zoneId` 对候选节点分组。
2. 每个 Zone 独立构建 `LcmSolution` 并并行求解。
3. 从各 Zone 求解结果中选择最优可行解。
4. 如果所有 Zone 都不可分配，则作业保持 `PENDING`。

## 5. 调度与执行衔接

调度只负责选择资源，但当前主干已经把执行策略一起打通：

- `EXEC_DOCKER`
- `EXEC_SHELL`
- `EXEC_ANSIBLE`
- `EXEC_SSH`

Dispatcher 会根据 `executionType` 把作业映射到对应命令类型，并把 `executionPayload` 一并带到 Satellite。

## 6. API 与前端触点

| 接口 / 页面 | 作用 |
|-------------|------|
| `POST /api/jobs` | 提交调度任务 |
| `POST /api/v1/allocations` | 发起显式资源分配 |
| `GET /api/nodes` | 提供节点与拓扑展示数据 |
| `TopologyPage` | 展示 Zone / Rack / IB Fabric 视图与已分配作业 |
| `JobsPage` / `JobDetailPage` | 展示调度结果与执行状态 |

## 7. 回归保障

当前已存在的相关回归包括：

- Core E2E 调度主链路测试
- `PartitionedSchedulingServiceTest`
- `JobDispatcherTest`
- `TopologyPage.test.tsx`
- `JobDetailPage.test.tsx`
- Trace context 透传相关测试

## 8. 仍需增强的方向

- 更多失败重试、断连恢复、压力级场景的集成测试
- 更高的覆盖率门槛与性能趋势基线
- 与 PXE 裸机交付和更完整多集群运维动作的深度联动

## 9. 结论

资源调度模块当前已经不只是“求解器接入”，而是具备了从请求进入、分区求解、状态持久化、执行派发到 UI 刷新的完整闭环。后续工作重点是继续增强异常场景和生产化联动，而不是推翻现有设计。
