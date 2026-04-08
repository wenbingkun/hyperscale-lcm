# Hyperscale LCM 开发规划路线图 (Development Roadmap)

本路线图旨在将 `hyperscale-lcm` 从原型构建为可管理数万台服务器的企业级平台。

> 最后更新: 2026-04-08

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
    *   [ ] 完善分区并行调度（按 Zone 分片）(Optional — 未来迭代)。
    *   [x] 独立调度 API：`POST /api/v1/allocations`。

## 📅 阶段四：执行与交付 (Execution & Delivery) ✅ 已完成
**目标**: 闭环"分配-执行"流程。
*   **Execution**:
    *   [x] Docker 容器执行 + Kafka 状态回调。
    *   [ ] 集成 Ansible 或 SSH 库，实现对被选定机器的命令下发 (Optional — 未来迭代)。
    *   [ ] (高级) 集成 PXE/iPXE，实现裸金属 OS 自动化重装。
*   **Frontend**:
    *   [x] React Dashboard 大屏（7 页面 + 5 组件 + WebSocket）。
    *   [x] 前端登录流程 + Token 管理。
    *   [x] 强化 UI/UX 动效与 Redfish 深层指标展示。
    *   [ ] 调度结果可视化（拓扑图展示分配情况）(Optional — 未来迭代)。

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

## 📅 阶段六：质量加固与落地推进 (Quality Hardening & Delivery Excellence) 🚧 进行中
**目标**: 收敛 MVP 后续的质量、可观测性与落地缺口，推动项目从“功能可用”进入“可持续演进”。
*   **质量**:
    *   [x] 前端测试补全 — 为 Dashboard、Job 管理、设备发现建立 `Vitest + React Testing Library` 回归测试，并补充 AuthContext / JobSubmissionForm 基础用例。
    *   [ ] 测试覆盖率量化 — 为 Core JaCoCo 设定基线门槛（如 60%）并在 CI 中卡控。
    *   [ ] 集成测试增强 — 补齐 Satellite 注册 → Core 调度 → Kafka 回调 → 前端刷新等跨服务场景。
*   **调度与执行**:
    *   [ ] 分区并行调度（按 Zone 分片）。
    *   [ ] 集成 Ansible 或 SSH 库，实现对被选定机器的命令下发。
    *   [ ] 集成 PXE/iPXE，实现裸金属 OS 自动化重装。
*   **可观测性与运维**:
    *   [ ] OpenTelemetry 全链路串联 — 补全 Satellite → Kafka → Core 的 trace propagation。
    *   [ ] AlertManager 集成（邮件 / Slack / PagerDuty 通知）。
    *   [ ] 真实硬件验证 — 使用真实 Redfish / BMC 环境验证采集链路。
*   **展示与落地**:
    *   [ ] 调度结果拓扑图可视化（GPU / NVLink / IB Fabric 分配展示）。
    *   [ ] 编写完整 Demo 脚本（零接触发现 → 自动纳管 → 调度 → 执行）。

---

## 🎯 当前状态 (Current Status)

**Sprint 6 (Audit Fix & Monitoring)** — ✅ 已完成

完成内容：
1.  ✅ **Grafana 监控栈** — 添加 Grafana 服务、预配置仪表盘、Prometheus 告警规则
2.  ✅ **E2E 测试修复** — 启用 Quarkus DevServices (Testcontainers) 支持自包含测试
3.  ✅ **CI 覆盖率** — CI 流水线集成 JaCoCo 覆盖率验证 (`gradlew check`)
4.  ✅ **Prometheus 生产配置** — 分离 dev/prod 配置，修复容器间服务发现
5.  ✅ **Helm 监控模板** — ServiceMonitor、PrometheusRule、Grafana Dashboard ConfigMap

**Sprint 7 (Frontend Quality Hardening)** — 🚧 进行中

完成内容：
1.  ✅ **前端测试基础设施** — 接入 `Vitest + React Testing Library + jsdom`
2.  ✅ **核心页面测试补齐** — 覆盖 Dashboard、Job 管理、设备发现
3.  ✅ **关键前端状态测试** — 补充 AuthContext、JobSubmissionForm 回归用例
4.  ✅ **前端质量清理** — 修复 `DiscoveryPage` 的 `react-hooks/exhaustive-deps` 告警
5.  ✅ **测试验证** — `cd frontend && npm test`（5 个测试文件 / 10 个用例通过）与 `cd frontend && npm run lint` 通过

## 🔍 项目评估 (Project Assessment)

*   **整体状态**:
    *   MVP 已完成，当前进入稳定期与质量加固阶段。
    *   近期推进重点从 CI 审计修复转向前端快速回归能力建设。
*   **子系统概况**:
    *   Core (Java/Quarkus): API / Service / E2E / Solver 覆盖相对完善。
    *   Satellite (Go): discovery / redfish / pxe / executor 等路径已有基础测试。
    *   Frontend (React): 已建立首批 `Vitest + React Testing Library` 单元 / 组件测试能力，仍需继续扩大覆盖率。
*   **关键结论**:
    *   [x] 前端零测试覆盖风险已开始收敛。
    *   [ ] Core 覆盖率基线与 CI 门禁仍待落地。
    *   [ ] 跨服务 E2E 场景仍需增强。
    *   [ ] Zone 分片、SSH / Ansible、拓扑可视化等差异化能力仍待补齐。
    *   [ ] 真实硬件 Redfish / BMC 验证与 OTel 全链路 propagation 仍待补齐。
