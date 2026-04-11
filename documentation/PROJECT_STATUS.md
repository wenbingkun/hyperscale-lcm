# Hyperscale LCM 项目现状 (Project Status)

> **Last Updated:** 2026-04-11
> **Basis:** main 分支当前代码（含 Phase 7 Redfish/BMC 管理平面落地提交 `44c9614`、`f427423`）
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
| JaCoCo 覆盖率门禁 | ✅ 已落地 | 当前基线 30%，实测 ~45.9%，长期目标 70% |
| OpenTelemetry 全链路追踪 | ✅ 已打通 | Satellite → Kafka → Core 状态回调已续接，有回归测试 |
| CI 质量门禁（CodeQL / gradle check / npm / go test） | ✅ 已落地 | |
| 集成测试（Testcontainers / DevServices / E2EIntegrationTest） | 🟡 主链路覆盖 | 失败重试、断连恢复、压力级故障场景仍不充分 |
| Prometheus / Grafana 仪表盘与告警规则 | ✅ 已落地 | 规则已配置，但 AlertManager 外部通道未打通 |

### 1.2 功能深化与自动化

| 能力 | 状态 | 备注 |
|------|------|------|
| Zone 分区并行调度 | ✅ 已落地 | `PartitionedSchedulingService` + 回归测试 |
| 拓扑可视化（Zone / Rack / IB Fabric） | ✅ 已落地 | `TopologyPage.tsx` 真实结构展示 |
| 设备自动发现（DHCP / ARP） | ✅ 已落地 | 已接入发现设备池与 claim 规划 |
| 多执行模式（Docker / Shell / Ansible / SSH） | ✅ 已落地 | `EXEC_*` 全量 + 单元/E2E 覆盖 |
| Redfish / BMC 管理平面（Phase 7） | ✅ 已落地 | Session 认证、HttpClient 统一底座、凭据档案打通 |
| PXE / iPXE 裸金属装机 | 🟡 80% | TFTP/iPXE/Cloud-Init 齐备；DHCP option 66/67 联动与镜像管理仍缺 |

### 1.3 生产运营能力

| 能力 | 状态 | 备注 |
|------|------|------|
| AlertManager 外部通知（邮件/Slack/PagerDuty） | ❌ 未完成 | **P0 阻塞项** |
| 负载测试自动化（loadgen + CI load-test） | 🟡 95% | 缺明确的回归阈值与趋势基线 |
| 多集群管理（clusterId 隔离、集群汇总 API） | 🟡 80% | 字段、调度隔离、查询 API 已具备；生命周期管理与联邦能力未完成 |
| 真实硬件 Redfish/BMC 验收 | 🟡 框架就绪 | `documentation/hardware-acceptance/` 已建立 matrix + 单机型样板（openbmc），待接入更多厂商 |
| Demo 脚本 | 🟡 基础可用 | `scripts/demo.sh` 已有基础流程，仍需扩展到"零接触发现 → 纳管 → 调度 → 执行"完整闭环 |

---

## 2. 架构现状概览

### 2.1 分层与通信

```
Frontend (React 19 + Vite)
  ├─ REST + WebSocket
  ▼
Core (Java 21 + Quarkus 3.6.4 + Timefold 1.4.0)
  ├─ PostgreSQL 15 + Flyway（当前 V2.6.0，16 个迁移脚本）
  ├─ Redis（Satellite 在线状态缓存）
  ├─ Kafka（jobs.scheduled / jobs.execution / jobs.status / DLQ）
  ├─ gRPC + mTLS
  ▼
Satellite (Go 1.24)
  └─ Redfish / 网络发现 / PXE / Docker / Shell / Ansible / SSH
```

控制面与数据面边界清晰：`gRPC + mTLS` 负责实时控制、Kafka 负责调度执行回调闭环、WebSocket 负责前端实时态。

### 2.2 核心数据模型

`Satellite` · `Node` · `Job` · `Tenant` · `ResourceQuota` · `AuditLog` · `DiscoveredDevice` · `CredentialProfile` · `ScanJob` · `Allocation`

### 2.3 调度引擎

基于 **Timefold Solver 1.4.0**，约束维度包括：GPU 型号匹配、NVLink / NVSwitch 拓扑亲和性、IB Fabric 优化、租户与资源条件。`SchedulingService` 与 `PartitionedSchedulingService` 均已按 `clusterId` 过滤活跃节点。独立分配 API：`POST /api/v1/allocations`。

### 2.4 安全体系

JWT · RBAC · gRPC mTLS 双向认证 · 多租户资源配额 · 测试/开发证书自动生成（`./scripts/generate_keys.sh`）均已落地。

### 2.5 可观测性

Prometheus 指标 · Grafana 仪表盘 · Jaeger / OpenTelemetry 接线 · Satellite → Kafka → Core 的 `traceContext` 续接 · WebSocket 实时仪表盘 · Liveness/Readiness 健康检查均已就位。**缺口：** AlertManager 外部通道。

---

## 3. 已知缺口与下阶段重点

| 优先级 | 事项 | 阻塞类别 | 预期效果 |
|--------|------|---------|---------|
| 🔴 P0 | AlertManager 外部通知打通（邮件 / Slack / PagerDuty） | 可运维性 | 告警真正触达值班人 |
| 🔴 P0 | 真实硬件 Redfish / BMC 验收扩面（Dell iDRAC / HPE iLO / Lenovo XCC） | 生产接入 | 降低硬件接入风险 |
| 🟡 P1 | PXE / iPXE 闭环补齐（DHCP option 66/67、镜像管理、动态 Cloud-Init） | 自动化交付 | 裸机交付可自动化 |
| 🟡 P1 | 完整 Demo 脚本扩展（发现 → 纳管 → 调度 → 执行） | 演示与验收 | 降低演示/培训/验收成本 |
| 🟡 P1 | Playwright E2E 扩充（登录、发现、作业提交、拓扑） | 回归保护 | 浏览器级关键流程覆盖 |
| 🟠 P2 | 覆盖率门槛从 30% 渐进提升至 70% | 工程质量 | 长期目标收敛 |
| 🟠 P2 | 负载测试回归阈值与趋势基线 | 可比较性 | 性能验证从"能跑"到"可比较" |
| 🟠 P2 | 多集群联邦与生命周期管理（Cluster CRUD、多 Core 协调） | 规模化运营 | 跨数据中心统一运营 |

**建议执行顺序：**
- **近期 1-2 周：** P0 两项 + PXE 闭环
- **中期 3-4 周：** Demo 脚本、Playwright 扩充、负载基线
- **远期 5-8 周：** 多集群联邦增强

---

## 4. 本地验证矩阵

| 目标 | 命令 | 说明 |
|------|------|------|
| CI Contract 快速 guard | `./scripts/check_ci_contract.sh` | 高风险改动前置检查 |
| Core | `cd core && ./gradlew check --no-daemon` | 含 JaCoCo 门禁 |
| Satellite | `cd satellite && go test ./... -count=1` | Go 1.24 环境 |
| Frontend | `cd frontend && npm test && npm run lint && npm run build` | Vitest + ESLint + Vite |

详细运行时验证矩阵参见 [CI_CONTRACT.md](CI_CONTRACT.md)。

---

## 5. 文档索引

| 类别 | 文档 |
|------|------|
| 路线图与历史 | [../DEVELOPMENT_ROADMAP.md](../DEVELOPMENT_ROADMAP.md) |
| 架构设计 | [ENTERPRISE_LCM_ARCHITECTURE.md](ENTERPRISE_LCM_ARCHITECTURE.md) · [RESOURCE_SCHEDULING_DESIGN.md](RESOURCE_SCHEDULING_DESIGN.md) |
| CI/CD 事实源 | [CI_CONTRACT.md](CI_CONTRACT.md) · [CI_FAILURE_PATTERNS.md](CI_FAILURE_PATTERNS.md) |
| Phase 7 活跃计划 | [REDFISH_BMC_PHASE7_PLAN.md](REDFISH_BMC_PHASE7_PLAN.md) |
| 硬件验收 | [hardware-acceptance/README.md](hardware-acceptance/README.md) |
| Demo | [DEMO_GUIDE.md](DEMO_GUIDE.md) |
