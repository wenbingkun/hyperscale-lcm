# Hyperscale LCM 开发规划路线图 (Development Roadmap)

本路线图旨在将 `hyperscale-lcm` 从原型构建为可管理数万台服务器的企业级平台。

> 最后更新: 2026-04-14 (Phase 7 Core 管理面与前端闭环落地)

## 📅 阶段一：地基与连接 (Foundation & Connectivity) ✅ 已完成
**目标**: 打通 Core 与 Satellite 的通信，实现基础资产数据上报。
*   **Core (Quarkus)**:
    *   [x] 设计并实现领域模型：`Satellite`, `Node`, `Job`, `Tenant`, `ResourceQuota`, `AuditLog`。
    *   [x] 集成 gRPC 服务端，定义 `lcm.proto` 协议（Register, Heartbeat, Discovery, ConnectStream）。
    *   [x] 实现资产注册 API（REST + gRPC）。
    *   [x] Flyway 数据库迁移 V1.0.0 ~ V1.5.0。
    *   [x] JWT 认证与 RBAC 权限控制。
    *   [x] Kafka 消息闭环（调度 → 执行 → 回调）。
    *   [x] WebSocket 实时状态推送。
*   **Satellite (Go)**:
    *   [x] 实现 gRPC 客户端，完成与 Core 的 mTLS 握手。
    *   [x] 实现基础的数据采集（当前 Mock 数据）。
    *   [x] 实现心跳机制 (Heartbeat) + KeepAlive。
    *   [x] 双向流命令通道 (ConnectStream)。
    *   [x] Docker 容器执行器。
*   **基础设施**:
    *   [x] Docker Compose (Postgres, Redis, Kafka, Jaeger, Prometheus)。
    *   [x] mTLS 证书自动生成。
    *   [x] 万级节点负载测试工具 (loadgen)。

## 📅 阶段二：主动发现与深度采集 (Active Discovery & Deep Inspection) ✅ 已完成
**目标**: 实现"零接触"纳管，自动发现网络中的新设备并获取详细硬件信息。
*   **Satellite**:
    *   [x] 实现网络扫描器（Ping Scan）。
    *   [x] 发现事件通过 gRPC 上报 Core。
    *   [x] **真实指标采集** — 替换 Mock 心跳数据。
    *   [x] 实现 DHCP Listener + ARP Scanner，零接触设备发现。
    *   [x] 实现 Redfish Client，远程获取 BMC 信息（电源、温度）。
*   **Core**:
    *   [x] **DiscoveredDevice 持久化** — 补全 reportDiscovery TODO。
    *   [x] **Node 实体与 Satellite 硬件数据同步**。
    *   [x] 实现"待纳管设备池" (Discovered Pool) 审批流程。
    *   [x] 纳管策略引擎（自动/手动批准）。

## 📅 阶段三：智能调度与资源池化 (Intelligent Scheduling) ✅ 已完成
**目标**: 实现基于 Timefold 的 AI 算力调度。
*   **Core**:
    *   [x] GPU 拓扑模型 (NVLink, NVSwitch, IB Fabric 建模)。
    *   [x] Timefold 约束求解器集成（GPU 型号匹配、拓扑感知）。
    *   [x] PartitionedSchedulingService 分区调度框架。
    *   [x] 完善分区并行调度（按 Zone 分片）(Optional — 已完成)。
    *   [x] 独立调度 API：`POST /api/v1/allocations`。

## 📅 阶段四：执行与交付 (Execution & Delivery) ✅ 已完成
**目标**: 闭环"分配-执行"流程。
*   **Execution**:
    *   [x] Docker 容器执行 + Kafka 状态回调。
    *   [x] 集成 Ansible 或 SSH 库，实现对被选定机器的命令下发 (Optional — 已完成)。
    *   [x] (高级) 集成 PXE/iPXE，实现裸金属 OS 自动化重装（Sprint 16 完成）。
*   **Frontend**:
    *   [x] React Dashboard 大屏（7 页面 + 5 组件 + WebSocket）。
    *   [x] 前端登录流程 + Token 管理。
    *   [x] 强化 UI/UX 动效与 Redfish 深层指标展示。
    *   [x] 调度结果可视化（拓扑图展示分配情况）(Optional — 已完成)。

## 📅 阶段五：生产就绪 (Production Ready) ✅ 已完成
**目标**: 达到可部署的 MVP 水平。
*   **DevOps**:
    *   [x] Kubernetes 清单（Deployment, DaemonSet, ConfigMap, Secrets）。
    *   [x] Helm Chart 打包（含 ServiceMonitor + PrometheusRule + Grafana ConfigMap）。
    *   [x] CI/CD 流水线 (GitHub Actions) + JaCoCo 覆盖率检查。
    *   [x] CodeQL 静态安全分析 (Go, Java, JavaScript)。
    *   [x] 性能基准测试自动化 (Loadgen)。
*   **质量**:
    *   [x] 基础测试套件（Auth, Job, Satellite, StateCache, ConstraintProvider, AlertService）。
    *   [x] E2E 集成测试（Quarkus DevServices + Testcontainers）。
    *   [x] 代码审计修复 — ESLint 错误、重复依赖、TODO 占位符清理。
    *   [x] DLQ (Dead Letter Queue) 异常消息处理机制。
    *   [x] Satellite 环境变量配置化（替换硬编码参数）。
    *   [x] OpenTelemetry 端到端链路追踪（Core → Satellite → Kafka）。
*   **监控**:
    *   [x] Prometheus 指标采集 + 告警规则。
    *   [x] Grafana 监控仪表盘（集群概览、Job 指标、JVM 内存、HTTP 请求）。

## 📅 阶段六：质量加固与落地推进 (Quality Hardening & Delivery Excellence) ✅ 已完成
**目标**: 收敛 MVP 后续的质量、可观测性与落地缺口，推动项目从”功能可用”进入”可持续演进”。
*   **质量**:
    *   [x] 前端测试补全 — 为 Dashboard、Job 管理、设备发现建立 `Vitest + React Testing Library` 回归测试，并补充 AuthContext / JobSubmissionForm 基础用例。
    *   [x] 测试覆盖率量化 — Core JaCoCo 基线从 `30%` → `50%`；当前实测指令覆盖率为 `58.08%`。前端 Istanbul Statements `65.23%` / Lines `65.24%`。
    *   [x] 集成测试增强 — 补齐 Satellite 注册 → Core 调度 → Kafka 回调 → 前端刷新等跨服务场景，并修复调度后 Job 状态未从 `PENDING` 推进到 `SCHEDULED` 的一致性缺口。
*   **调度与执行**:
    *   [x] 分区并行调度（按 Zone 分片）。
    *   [x] 集成 Ansible 或 SSH 库，实现对被选定机器的命令下发。
    *   [x] 集成 PXE/iPXE，实现裸金属 OS 自动化重装（DHCP Proxy + TFTP + iPXE + HTTP kickstart + 镜像管理 API）。
*   **可观测性与运维**:
    *   [x] OpenTelemetry 全链路串联 — 补全 Satellite → Kafka → Core 的 trace propagation。
    *   [x] AlertManager 集成 — Core 主动推送告警至 AlertManager，Helm Chart 含 AlertManager 模板，docker-compose.prod 含 AlertManager 容器。
    *   [x] 模拟 BMC 验证 — 基于共享 vendor fixture + HTTPS mock 补齐 OpenBMC / iDRAC / iLO / XCC 的仿真验收矩阵。
    *   [ ] 真实硬件验证 — 使用真实 Redfish / BMC 环境验证采集链路（需要真实硬件环境）。
*   **安全加固**:
    *   [x] Grafana 默认凭据参数化、WebSocket JWT 认证、REST API 按角色速率限制。
    *   [x] Helm Chart 补齐 NetworkPolicy / PDB / ServiceAccount & RBAC。
*   **展示与落地**:
    *   [x] 调度结果拓扑图可视化（GPU / NVLink / IB Fabric 分配展示）。
    *   [x] 编写完整 Demo 脚本（零接触发现 → 自动纳管 → 调度 → 执行）。
    *   [x] 大组件拆分重构（CredentialProfilesPage / DiscoveryPage）。

---

## 📅 阶段七：Redfish/BMC 协议深化与硬件准入 🟢 Core 闭环
**目标**: 在现有 claim / managed-account / 仿真验收基础上，把 Redfish/BMC 从“可验证”推进到“可管理、可准入、可运维”。
*   **协议与认证深化**:
    *   [x] 统一 `session-aware` Redfish 传输层与能力探测策略（`RedfishTransport + RedfishSessionManager`，统一承接 GET/POST/PATCH/DELETE 与 401 自动重建）。
    *   [x] 明确 `SESSION_PREFERRED` 默认策略，并支持设备级覆盖（`DiscoveredDevice.redfishAuthModeOverride` + `CredentialProfile.redfishAuthMode`）。
*   **BMC 管理平面**:
    *   [x] 新增 `/api/bmc/devices/{id}/{capabilities,claim,rotate-credentials,power-actions}` 管理接口；旧 `/api/discovery/{id}/...` 入口标记 `@Deprecated(forRemoval=true)` 作薄转发。
    *   [x] 引入受控 `power-actions`（白名单 + `Idempotency-Key` + 显式 `systemId`）、`AuditLog` BMC 事件类型与 `lcm_bmc_power_action_total` / `lcm_bmc_session_reauth_total` 指标。
    *   [x] 前端 `DiscoveryPage` 接入 BMC 能力快照、凭据轮换、`dry-run` 预演与 ADMIN 角色强校验。
*   **采集与验收扩展**:
    *   [ ] Satellite 补齐 `session-aware` 只读采集能力（**延后到 Phase 8**，Core 侧 `RedfishMockServer.withSessionService()` 已覆盖协议路径）。
    *   [ ] 扩展真实硬件准入矩阵，覆盖 OpenBMC 与至少一种商业 BMC（框架与样板已就绪，等待实验台数据填入）。
*   **参考文档**:
    *   [x] 详细方案见 `documentation/REDFISH_BMC_PHASE7_PLAN.md`。

---

## 🎯 当前聚焦 (Current Focus)

本路线图现在只保留“阶段目标、阶段完成情况、项目历程”三类信息，滚动现状不再在此重复维护。

*   **滚动现状快照**: 统一见 `documentation/PROJECT_STATUS.md`
*   **文档入口导航**: 统一见 `README.md`
*   **Redfish/BMC 当前专项计划**: 统一见 `documentation/REDFISH_BMC_PHASE7_PLAN.md`

当前仍在推进或待完成的高优先级事项：
*   [ ] 真实硬件 Redfish / BMC 验收扩面（OpenBMC + 至少一种商业 BMC）
*   [ ] AlertManager 外部通知通道（邮件 / Slack / PagerDuty）
*   [ ] Satellite session-aware 只读采集（延后自 Phase 7）
*   [ ] PXE / iPXE 生产硬化与真实环境验证
*   [ ] Demo smoke 扩展与 Playwright 浏览器级回归

---

## 📈 项目历程回顾 (Project History)

> 基于 git 提交历史整理，共 119 commits（main 分支），2026-01-16 ~ 2026-04-09。

### 2026-01 — 项目启动与基础框架搭建

*   **v1.0.0 初始化** (01-16) — 建立 Core (Quarkus) + Satellite (Go) + Frontend (React) 三端骨架
*   **核心能力落地** — Token 刷新端点、WebSocket 实时推送、Dashboard UI 集成、网络自动扫描、GPU 拓扑可视化
*   **生产就绪基础** — Helm Chart、Playwright E2E 测试、代码拆分与懒加载
*   **阶段一~四功能批量交付** — 设备发现、租户管理、gRPC 通信、Kafka 消息闭环

### 2026-02 ~ 2026-03 上旬 — 功能深化与 CI 稳定化

*   **多集群支持** — `clusterId` 模型引入，调度隔离，Cluster API 与负载测试基线
*   **执行能力扩展** — Ansible / Shell 远程命令执行器、PXE 双 TFTP/HTTP 服务嵌入
*   **CI 反复修复周期** — gRPC 端口冲突、健康探针路径、JaCoCo 数据路径、Kafka 服务依赖、负载测试稳定性等 ~15 个 CI 修复提交
*   **Redfish 零接触纳管** — BMC 凭据声明、轮换、CMDB 同步与 E2E 测试
*   **CLAUDE.md 与 CI Contract 机制建立** — 引入工程协作规范与 CI 契约守卫脚本

### 2026-03 下旬 ~ 2026-04 — 质量加固与可观测性收敛

*   **Sprint 7** — 前端测试基础设施（Vitest + RTL），5 个页面级测试文件
*   **Sprint 8** — JaCoCo 覆盖率基线（30%），当前实测 ~45%
*   **Sprint 9** — 跨服务集成测试增强，调度状态持久化补强
*   **Sprint 10** — Zone 分区调度回归测试，`LcmSolverFacade` 抽象
*   **Sprint 11** — 多执行模式建模（Docker / Shell / Ansible / SSH），SSH 执行器
*   **Sprint 12** — 拓扑分配可视化，Zone / Rack / NVLink / IB Fabric 渲染
*   **Sprint 13** — OTel trace propagation 补全，Satellite → Kafka → Core 链路续接
*   **代码审查修复** (04-08) — 安全、运行时与追踪问题修复，项目文档整合刷新
*   **Sprint 14** — 安全加固：Grafana 凭据参数化、WebSocket JWT 鉴权、REST API 速率限制；Core Service/Resource 测试补齐；JaCoCo 基线 → 45%
*   **Sprint 15** — AlertManager 部署集成、Helm NetworkPolicy / PDB / ServiceAccount / RBAC 模板、前端覆盖率报告与组件测试
*   **Sprint 16** — PXE 全链路闭环（DHCP Proxy → TFTP → iPXE → kickstart + 镜像管理）、大组件拆分、端到端 Demo 脚本、JaCoCo 基线 → 50%

### 关键数据

| 指标 | 数值 |
|------|------|
| 总提交数 (main) | 119 |
| 开发周期 | 约 12 周（2026-01-16 ~ 2026-04-09） |
| 已完成 Sprint | 16 |
| Flyway 迁移版本 | V1.0.0 ~ V2.6.0（16 个脚本） |
| Core 测试类 | 32 个（Service 20 + API Resource 12） |
| Satellite 测试文件 | 11 个 |
| Frontend 测试文件 | 12 个 / 21 个用例 |
| CI 工作流 Job | 6 个（contract-guard / backend / frontend / satellite / load-test / docker-build） |
| Core JaCoCo 指令覆盖率 | 58.08%（基线门槛 50%） |
| Frontend Istanbul Statements | 65.23%（Lines 65.24%） |
