# Hyperscale LCM 项目现状 (Project Status)

> **Last Updated:** 2026-06-14 (Offline/restricted-network release bundle runbook and helper script added to reduce the remaining Deployment Closure Step 5 registry-access blocker)
> **Maintenance:** 本文件为**滚动更新**的唯一现状快照，禁止再新增带日期后缀的 audit/analysis 文档。后续阶段进展应直接在本文件内更新章节并刷新顶部日期。
>
> **定位与 DEVELOPMENT_ROADMAP.md 的关系：**
> - `DEVELOPMENT_ROADMAP.md` 记录**路线图与阶段历史**（Phase 1..N 规划、目标、历史节奏）
> - 本文件记录**当前能力矩阵、架构现状、已知缺口、下阶段重点**，是给新成员或 AI agent 快速建立认知的入口
> - 当前阶段主计划见 [DEPLOYMENT_CLOSURE_PHASE_PLAN.md](DEPLOYMENT_CLOSURE_PHASE_PLAN.md)（Deployment Closure，2026-06-12 落地，Step 5 验收走查待执行；registry 受限时可先走 offline bundle）；上一阶段见 [SOFTWARE_CLOSURE_PHASE_PLAN.md](SOFTWARE_CLOSURE_PHASE_PLAN.md)

---

## 1. 能力矩阵（加权完成度）

### 1.1 工程质量与可观测性

| 能力 | 状态 | 备注 |
|------|------|------|
| JaCoCo 覆盖率门禁 | ✅ 已落地 | 当前基线 50%，最近一次文档化本地验证为 58.08% |
| OpenTelemetry 全链路追踪 | ✅ 已打通 | Satellite → Kafka → Core 状态回调已续接，有回归测试 |
| CI 质量门禁（CodeQL / gradle check / npm / go test） | ✅ 已落地 | |
| 集成测试（Testcontainers / DevServices / E2EIntegrationTest） | ✅ 主链路 + 断连恢复覆盖 | Playwright E2E（API mock 级）覆盖七大前端主流程；CI `demo-smoke` job 补齐真实 Core + Satellite + gRPC + Kafka + WebSocket 端到端链路；Satellite 命令流断连重连由 `satellite/pkg/stream` 单测回归覆盖、DLQ 路由有真实 broker E2E 断言（2026-06-12 [STABILITY_CLOSURE_PHASE_PLAN.md](STABILITY_CLOSURE_PHASE_PLAN.md)）；压力级故障注入与进程级重启恢复由 [deployment.md §5](runbooks/deployment.md) 人工 checklist 承接 |
| Prometheus / Grafana 仪表盘与告警规则 | ✅ 已落地 | 仪表盘、规则、AlertManager receiver 链路已就绪（默认 disabled，需按 [runbook](runbooks/alertmanager.md) 注入真实 secret 生效） |

### 1.2 功能深化与自动化

| 能力 | 状态 | 备注 |
|------|------|------|
| Zone 分区并行调度 | ✅ 已落地 | `PartitionedSchedulingService` + 回归测试 |
| 拓扑可视化（Zone / Rack / IB Fabric） | ✅ 已落地 | `TopologyPage.tsx` 真实结构展示 |
| 设备自动发现（DHCP / ARP） | ✅ 已落地 | 已接入发现设备池与 claim 规划 |
| 多执行模式（Docker / Shell / Ansible / SSH） | ✅ 已落地 | `EXEC_*` 全量 + 单元/E2E 覆盖 |
| 模拟 Redfish / BMC 验收矩阵 | ✅ 已落地 | OpenBMC / iDRAC / iLO / XCC 共享 fixture + HTTPS mock 已接入 |
| Redfish / BMC Phase 7 + Phase 8 深化 | ✅ 已落地 | Core 统一 `RedfishTransport + RedfishSessionManager`、`/api/bmc/devices/{id}/...` 管理面、受控 `power-actions`、审计/指标、前端 ADMIN 闭环、Satellite session-aware 只读 + fixture BASIC/SESSION 回归 + 优雅退出 session cleanup 均已落地；真实硬件准入矩阵扩面骨架见 [REDFISH_BMC_PHASE8_PLAN.md](REDFISH_BMC_PHASE8_PLAN.md)，真实机型验收数据仍待填充 |
| PXE / iPXE 裸金属装机 | 🟡 软件闭环已具备 | [runbooks/pxe.md](runbooks/pxe.md) 已补齐生产硬化前提、网络要求、镜像准备与失败回退；真实环境验证仍待裸机节点到位 |

### 1.3 生产运营能力

| 能力 | 状态 | 备注 |
|------|------|------|
| AlertManager 外部通知（邮件/Slack/PagerDuty） | ✅ 代码就绪 | Helm values 参数化 + Secret 模板 + CI guard 已落地；默认 disabled，需按 [runbook](runbooks/alertmanager.md) 注入真实 secret 才生效。详见 [ALERTMANAGER_PHASE_PLAN.md](ALERTMANAGER_PHASE_PLAN.md) |
| 负载测试自动化（loadgen + CI load-test） | 🟡 95% | CI 阈值已固化，[LOAD_TEST_BASELINES.md](LOAD_TEST_BASELINES.md) 已记录最近 5 次绿色主线运行；后续转入滚动维护窗口 |
| 多集群管理（clusterId 隔离、集群汇总 API） | 🟡 80% | 字段、调度隔离、查询 API 已具备；生命周期管理与联邦能力未完成 |
| 真实硬件 Redfish/BMC 验收 | 🟡 骨架齐备 | `documentation/hardware-acceptance/` 已有 OpenBMC / iDRAC / iLO / XCC 四份 per-machine 样板 + `matrix.yaml` `pending:` 追踪，待填充真实实验台数据 |
| Demo 脚本 | ✅ 已落地 | `scripts/demo.sh` 本地闭环已接入 CI `demo-smoke` job 的真实后端门禁；实施方案见 [DEMO_SMOKE_PHASE_PLAN.md](DEMO_SMOKE_PHASE_PLAN.md) |
| Playwright 浏览器级回归 | ✅ 已落地 | 已建立基于 API mock 的 Chromium 回归套件（6 个 spec），覆盖登录、Dashboard、发现、作业、拓扑与卫星详情、凭据配置主流程，并接入 CI `frontend-e2e` job；真实后端链路由 `demo-smoke` job 补齐 |
| 版本化发布与部署 runbook | ✅ 已落地 | v0.1.0 tag + semver 镜像发布链路、[runbooks/deployment.md](runbooks/deployment.md)（compose/Helm 双轨 + mTLS Secret + 必填 env 契约）、[runbooks/offline-deployment.md](runbooks/offline-deployment.md)（离线/受限网络 release bundle + dry-run + `manifest.env`）、[runbooks/upgrade-and-backup.md](runbooks/upgrade-and-backup.md)、CHANGELOG；helm/k8s/compose 的 mTLS 部署链路已修通，prod profile 缺失必填配置时 fail-fast，prod compose 依赖镜像已避免 `latest`，compose preflight 脚本可提前暴露缺镜像/缺证书/变量缺失。干净环境验收走查（[deployment.md §5](runbooks/deployment.md)）待执行 |

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

> **当前阶段：Stability Closure 已落地（2026-06-12）**，同日 Deployment Closure 落地并发布 v0.1.0。阶段主计划与落地记录分别见 [STABILITY_CLOSURE_PHASE_PLAN.md](STABILITY_CLOSURE_PHASE_PLAN.md)、[DEPLOYMENT_CLOSURE_PHASE_PLAN.md](DEPLOYMENT_CLOSURE_PHASE_PLAN.md)；更早阶段见 [SOFTWARE_CLOSURE_PHASE_PLAN.md](SOFTWARE_CLOSURE_PHASE_PLAN.md)。

### 3.1 已落地产物（无外部依赖）

| 优先级 | 事项 | 对应计划 | 当前状态 |
|--------|------|---------|---------|
| 🔴 P0 | 断连恢复测试收口（satellite `pkg/stream` 重连回归 + DLQ 真实 broker E2E 断言） | [STABILITY_CLOSURE_PHASE_PLAN.md](STABILITY_CLOSURE_PHASE_PLAN.md) | 已落地（2026-06-12） |
| 🔴 P0 | v0.1.0 版本化发布 + 部署链路修复（mTLS Secret 挂载、satellite env 契约、compose satellite 服务、prod fail-fast） | [DEPLOYMENT_CLOSURE_PHASE_PLAN.md](DEPLOYMENT_CLOSURE_PHASE_PLAN.md) | 已落地（2026-06-12）；唯 Step 5 干净环境验收走查待执行，registry 受限时先用 [runbooks/offline-deployment.md](runbooks/offline-deployment.md) dry-run 校验后制包导入 |
| 🔴 P0 | [runbooks/deployment.md](runbooks/deployment.md) + [runbooks/upgrade-and-backup.md](runbooks/upgrade-and-backup.md) | [DEPLOYMENT_CLOSURE_PHASE_PLAN.md §Step 3/4](DEPLOYMENT_CLOSURE_PHASE_PLAN.md) | 已落地 |
| 🔴 P0 | [runbooks/pxe.md](runbooks/pxe.md) — PXE 生产硬化 runbook | [SOFTWARE_CLOSURE_PHASE_PLAN.md §Step 2](SOFTWARE_CLOSURE_PHASE_PLAN.md#step-2--pxe--ipxe聚焦单一路径的生产硬化准备) | 已落地；真实裸机到位后可按 runbook 执行 |
| 🔴 P0 | [LOAD_TEST_BASELINES.md](LOAD_TEST_BASELINES.md) — load-test 趋势基线单一入口 | [SOFTWARE_CLOSURE_PHASE_PLAN.md §Step 3](SOFTWARE_CLOSURE_PHASE_PLAN.md#step-3--load-test从静态门槛升级为趋势基线) | 已落地；滚动维护中，最近 5 次绿色主线 run 已入库 |

### 3.2 受外部条件门控

| 优先级 | 事项 | 阻塞 | 解除条件 |
|--------|------|------|----------|
| 🔴 P0 | 真实硬件 Redfish / BMC 验收数据填充（`hardware-acceptance/matrix.yaml` `pending:` 骨架已就位，见 [REDFISH_BMC_PHASE8_PLAN.md](REDFISH_BMC_PHASE8_PLAN.md)） | 无真实 Dell iDRAC / HPE iLO / Lenovo XCC 设备 | 实验台硬件到位 |
| 🟡 P1 | AlertManager 真实 channel 接入与冒烟（代码与路由已就绪，需 prod secret 注入） | 无真实 Slack / PagerDuty / 邮件 secret | 按 [runbooks/alertmanager.md](runbooks/alertmanager.md) 完成真实送达；External Secrets / Vault / SOPS 选型延至 secret 可用前再决策 |
| 🟡 P1 | PXE / iPXE 真实环境验证（代码闭环已具备；runbook 见 §3.1） | 无真实裸机节点 | 裸机节点可用 |

### 3.3 长期收敛项

| 优先级 | 事项 | 阻塞类别 | 预期效果 |
|--------|------|---------|---------|
| 🟠 P2 | 覆盖率门槛从 50% 渐进提升至 70% | 工程质量 | 长期目标收敛 |
| 🟠 P2 | 多集群联邦与生命周期管理（Cluster CRUD、多 Core 协调） | 规模化运营 | 跨数据中心统一运营 |

**建议执行顺序：**
- **近期（条件具备即做）：** 在任一干净主机/k8s namespace 上执行 [deployment.md §5](runbooks/deployment.md) 验收走查（含三级重启恢复 checklist）；若目标环境 registry/Bitnami 访问受限，先按 [offline-deployment.md](runbooks/offline-deployment.md) 在联网制包机生成 release bundle 并导入目标环境。验收结果回填后关闭 Deployment Closure 最后一项。
- **外部条件一旦具备即触发：** 真实 secret 到位 → AlertManager 真实送达冒烟；裸机 / 商业 BMC 到位 → PXE 真实环境验证 + `hardware-acceptance/matrix.yaml` 扩面。
- **中期 3-4 周：** 覆盖率门槛渐进提升（50% → 55% → 60%）。
- **远期 5-8 周：** 多集群联邦与生命周期管理（Cluster CRUD）；如需 DLQ 回放机制，独立立项。

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
