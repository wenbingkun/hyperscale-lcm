# Hyperscale LCM 项目现状 (Project Status)

> **Last Updated:** 2026-04-15
> **Maintenance:** 本文件为**滚动更新**的唯一现状快照，禁止再新增带日期后缀的 audit/analysis 文档。后续阶段进展应直接在本文件内更新章节并刷新顶部日期。
>
> **定位与 DEVELOPMENT_ROADMAP.md 的关系：**
> - `DEVELOPMENT_ROADMAP.md` 记录**路线图与阶段历史**（Phase 1..N 规划、目标、历史节奏）
> - 本文件记录**当前能力矩阵、架构现状、已知缺口、下阶段重点**，是给新成员或 AI agent 快速建立认知的入口

---

## 1. 能力矩阵（加权完成度）

### 1.1 工程质量与可观测性

| 能力 | 状态 | 备注 |
|------|------|------|
| JaCoCo 覆盖率门禁 | ✅ 已落地 | 当前基线 50%，最近一次文档化本地验证为 58.08% |
| OpenTelemetry 全链路追踪 | ✅ 已打通 | Satellite → Kafka → Core 状态回调已续接，有回归测试 |
| CI 质量门禁（CodeQL / gradle check / npm / go test） | ✅ 已落地 | |
| 集成测试（Testcontainers / DevServices / E2EIntegrationTest） | 🟡 主链路覆盖 | 浏览器级 E2E、断连恢复和压力级故障场景仍不充分 |
| Prometheus / Grafana 仪表盘与告警规则 | 🟡 基础已落地 | 仪表盘和规则已接入，但 AlertManager 外部通知仍未闭环 |

### 1.2 功能深化与自动化

| 能力 | 状态 | 备注 |
|------|------|------|
| Zone 分区并行调度 | ✅ 已落地 | `PartitionedSchedulingService` + 回归测试 |
| 拓扑可视化（Zone / Rack / IB Fabric） | ✅ 已落地 | `TopologyPage.tsx` 真实结构展示 |
| 设备自动发现（DHCP / ARP） | ✅ 已落地 | 已接入发现设备池与 claim 规划 |
| 多执行模式（Docker / Shell / Ansible / SSH） | ✅ 已落地 | `EXEC_*` 全量 + 单元/E2E 覆盖 |
| 模拟 Redfish / BMC 验收矩阵 | ✅ 已落地 | OpenBMC / iDRAC / iLO / XCC 共享 fixture + HTTPS mock 已接入 |
| Redfish / BMC Phase 7 + Phase 8 深化 | ✅ 已落地 | Core 统一 `RedfishTransport + RedfishSessionManager`、`/api/bmc/devices/{id}/...` 管理面、受控 `power-actions`、审计/指标、前端 ADMIN 闭环、Satellite session-aware 只读 + fixture BASIC/SESSION 回归 + 优雅退出 session cleanup 均已落地；真实硬件准入矩阵扩面骨架见 [REDFISH_BMC_PHASE8_PLAN.md](REDFISH_BMC_PHASE8_PLAN.md)，真实机型验收数据仍待填充 |
| PXE / iPXE 裸金属装机 | 🟡 软件闭环已具备 | 生产硬化和真实环境验证仍待收敛 |

### 1.3 生产运营能力

| 能力 | 状态 | 备注 |
|------|------|------|
| AlertManager 外部通知（邮件/Slack/PagerDuty） | ❌ 未完成 | **P0 阻塞项** |
| 负载测试自动化（loadgen + CI load-test） | 🟡 95% | 缺明确的回归阈值与趋势基线 |
| 多集群管理（clusterId 隔离、集群汇总 API） | 🟡 80% | 字段、调度隔离、查询 API 已具备；生命周期管理与联邦能力未完成 |
| 真实硬件 Redfish/BMC 验收 | 🟡 骨架齐备 | `documentation/hardware-acceptance/` 已有 OpenBMC / iDRAC / iLO / XCC 四份 per-machine 样板 + `matrix.yaml` `pending:` 追踪，待填充真实实验台数据 |
| Demo 脚本 | ✅ 基础闭环可用 | `scripts/demo.sh` 已支持本地闭环；更广 smoke 覆盖仍待扩展 |
| Playwright 浏览器级回归 | 🟡 进行中 | 关键页面已有 Vitest，浏览器级主流程仍未形成体系 |

---

## 2. 架构现状概览

### 2.1 分层与通信

```
Frontend (React 19 + Vite)
  ├─ REST + WebSocket
  ▼
Core (Java 21 + Quarkus 3.6.4 + Timefold 1.4.0)
  ├─ PostgreSQL 15 + Flyway
  ├─ Redis（Satellite 在线状态缓存）
  ├─ Kafka（jobs.scheduled / jobs.execution / jobs.status / DLQ）
  ├─ gRPC + mTLS
  ▼
Satellite (Go 1.24)
  └─ Redfish / 网络发现 / PXE / Docker / Shell / Ansible / SSH
```

控制面与数据面边界清晰：`gRPC + mTLS` 负责实时控制，Kafka 负责调度执行回调闭环，WebSocket 负责前端实时态。

### 2.2 核心数据模型

`Satellite` · `Node` · `Job` · `Tenant` · `ResourceQuota` · `AuditLog` · `DiscoveredDevice` · `CredentialProfile` · `ScanJob` · `Allocation`

### 2.3 调度引擎

基于 **Timefold Solver 1.4.0**，约束维度包括：GPU 型号匹配、NVLink / NVSwitch 拓扑亲和性、IB Fabric 优化、租户与资源条件。`SchedulingService` 与 `PartitionedSchedulingService` 均已按 `clusterId` 过滤活跃节点。独立分配 API：`POST /api/v1/allocations`。

### 2.4 安全体系

JWT · RBAC · gRPC mTLS 双向认证 · 多租户资源配额 · 测试/开发证书自动生成（`./scripts/generate_keys.sh`）均已落地。

### 2.5 可观测性

Prometheus 指标、Grafana 仪表盘、Jaeger / OpenTelemetry 接线、Satellite → Kafka → Core 的 `traceContext` 续接、WebSocket 实时仪表盘与健康检查均已就位。详细拓扑与分层说明见 [ENTERPRISE_LCM_ARCHITECTURE.md](ENTERPRISE_LCM_ARCHITECTURE.md)。

---

## 3. 已知缺口与下阶段重点

| 优先级 | 事项 | 阻塞类别 | 预期效果 |
|--------|------|---------|---------|
| 🔴 P0 | AlertManager 外部通知打通（邮件 / Slack / PagerDuty） | 可运维性 | 告警真正触达值班人 |
| 🔴 P0 | 真实硬件 Redfish / BMC 验收数据填充（Dell iDRAC / HPE iLO / Lenovo XCC 骨架已就位，见 [REDFISH_BMC_PHASE8_PLAN.md](REDFISH_BMC_PHASE8_PLAN.md)） | 生产接入 | 降低硬件接入风险 |
| 🟡 P1 | PXE / iPXE 生产硬化与真实环境验证 | 自动化交付 | 降低裸机交付落地风险 |
| 🟡 P1 | Demo smoke 扩展与 Playwright E2E（登录、发现、作业提交、拓扑） | 回归保护 | 浏览器级关键流程覆盖 |
| 🟠 P2 | 覆盖率门槛从 50% 渐进提升至 70% | 工程质量 | 长期目标收敛 |
| 🟠 P2 | 负载测试回归阈值与趋势基线 | 可比较性 | 性能验证从"能跑"到"可比较" |
| 🟠 P2 | 多集群联邦与生命周期管理（Cluster CRUD、多 Core 协调） | 规模化运营 | 跨数据中心统一运营 |

**建议执行顺序：**
- **近期 1-2 周：** AlertManager 外部通道 + 真实硬件 Redfish/BMC 验收数据填充（骨架已齐）
- **中期 3-4 周：** Demo smoke、Playwright、PXE 生产硬化、负载基线
- **远期 5-8 周：** 多集群联邦增强

---

## 4. 本地验证矩阵

| 目标 | 命令 | 说明 |
|------|------|------|
| CI Contract 快速 guard | `./scripts/check_ci_contract.sh` | 高风险改动前置检查 |
| Core | `cd core && ./gradlew check --no-daemon` | 具体环境变量矩阵以 `CI_CONTRACT.md` 为准 |
| Satellite | `cd satellite && go test ./... -count=1` | Go 1.24 环境 |
| Frontend | `cd frontend && npm test && npm run lint && npm run build` | Vitest + ESLint + Vite |

详细运行时验证矩阵参见 [CI_CONTRACT.md](CI_CONTRACT.md)。

根目录文档导航统一见 [../README.md](../README.md) 的 `Documentation Map`。
