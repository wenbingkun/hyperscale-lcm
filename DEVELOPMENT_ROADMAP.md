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
    *   [x] 完善分区并行调度（按 Zone 分片）(Optional — 已完成)。
    *   [x] 独立调度 API：`POST /api/v1/allocations`。

## 📅 阶段四：执行与交付 (Execution & Delivery) ✅ 已完成
**目标**: 闭环"分配-执行"流程。
*   **Execution**:
    *   [x] Docker 容器执行 + Kafka 状态回调。
    *   [x] 集成 Ansible 或 SSH 库，实现对被选定机器的命令下发 (Optional — 已完成)。
    *   [ ] (高级) 集成 PXE/iPXE，实现裸金属 OS 自动化重装。
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

## 📅 阶段六：质量加固与落地推进 (Quality Hardening & Delivery Excellence) 🚧 进行中
**目标**: 收敛 MVP 后续的质量、可观测性与落地缺口，推动项目从“功能可用”进入“可持续演进”。
*   **质量**:
    *   [x] 前端测试补全 — 为 Dashboard、Job 管理、设备发现建立 `Vitest + React Testing Library` 回归测试，并补充 AuthContext / JobSubmissionForm 基础用例。
    *   [x] 测试覆盖率量化 — 为 Core JaCoCo 设定 `30%` 指令覆盖率基线并在 `check` 中卡控；当前实测指令覆盖率为 `42.88%`。
    *   [x] 集成测试增强 — 补齐 Satellite 注册 → Core 调度 → Kafka 回调 → 前端刷新等跨服务场景，并修复调度后 Job 状态未从 `PENDING` 推进到 `SCHEDULED` 的一致性缺口。
*   **调度与执行**:
    *   [x] 分区并行调度（按 Zone 分片）。
    *   [x] 集成 Ansible 或 SSH 库，实现对被选定机器的命令下发。
    *   [ ] 集成 PXE/iPXE，实现裸金属 OS 自动化重装。
*   **可观测性与运维**:
    *   [x] OpenTelemetry 全链路串联 — 补全 Satellite → Kafka → Core 的 trace propagation。
    *   [ ] AlertManager 集成（邮件 / Slack / PagerDuty 通知）。
    *   [ ] 真实硬件验证 — 使用真实 Redfish / BMC 环境验证采集链路。
*   **展示与落地**:
    *   [x] 调度结果拓扑图可视化（GPU / NVLink / IB Fabric 分配展示）。
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

**Sprint 7 (Frontend Quality Hardening)** — ✅ 已完成

完成内容：
1.  ✅ **前端测试基础设施** — 接入 `Vitest + React Testing Library + jsdom`
2.  ✅ **核心页面测试补齐** — 覆盖 Dashboard、Job 管理、设备发现
3.  ✅ **关键前端状态测试** — 补充 AuthContext、JobSubmissionForm 回归用例
4.  ✅ **前端质量清理** — 修复 `DiscoveryPage` 的 `react-hooks/exhaustive-deps` 告警
5.  ✅ **测试验证** — `cd frontend && npm test`（5 个测试文件 / 10 个用例通过）与 `cd frontend && npm run lint` 通过

**Sprint 8 (Coverage Baseline Enforcement)** — ✅ 已完成

完成内容：
1.  ✅ **JaCoCo 基线接线修复** — 修复 `core/build.gradle` 中覆盖率任务对执行数据文件的定位，避免 `jacocoTestReport` / `jacocoTestCoverageVerification` 被跳过
2.  ✅ **覆盖率门槛参数化** — 增加 `jacocoMinimumCoverage` 属性，默认基线为 `30%`
3.  ✅ **覆盖率量化** — 当前 Core 指令覆盖率实测为 `42.88%`
4.  ✅ **测试验证** — `./scripts/check_ci_contract.sh` 通过；按 compose 对齐环境执行 `cd core && env QUARKUS_DATASOURCE_REACTIVE_URL=postgresql://localhost:5432/lcm_db QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/lcm_db QUARKUS_DATASOURCE_USERNAME=lcm_user QUARKUS_DATASOURCE_PASSWORD=lcm_password QUARKUS_REDIS_HOSTS=redis://localhost:6379 KAFKA_BOOTSTRAP_SERVERS=localhost:9092 ./gradlew check --no-daemon` 通过

**Sprint 9 (Cross-Service Integration Hardening)** — ✅ 已完成

完成内容：
1.  ✅ **调度状态持久化补强** — 在非分区 / 分区调度找到目标节点后先将 Job 持久化为 `SCHEDULED`，补齐 `assignedNodeId` 与 `scheduledAt`
2.  ✅ **回调链路广播增强** — Job 状态回调除保留既有调度事件外，新增 `JOB_STATUS` WebSocket 广播，前端可按作业状态变化即时刷新
3.  ✅ **前端实时刷新补齐** — Job 列表页与详情页统一监听 `SCHEDULE_EVENT` / `JOB_STATUS`，新增详情页实时刷新测试
4.  ✅ **E2E 场景增强** — `E2EIntegrationTest` 现在校验 Satellite 注册 → Job 调度落库 → Kafka 状态回调 → Job 完成状态持久化的完整链路，并按 `jobId` 精确匹配 `jobs.status` 主题消息
5.  ✅ **测试验证** — `./scripts/check_ci_contract.sh` 通过；`cd frontend && npm test`（6 个测试文件 / 12 个用例通过）、`cd frontend && npm run lint`、`cd frontend && npm run build` 通过；按 compose 对齐环境执行 `cd core && env QUARKUS_DATASOURCE_REACTIVE_URL=postgresql://localhost:5432/lcm_db QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/lcm_db QUARKUS_DATASOURCE_USERNAME=lcm_user QUARKUS_DATASOURCE_PASSWORD=lcm_password QUARKUS_REDIS_HOSTS=redis://localhost:6379 KAFKA_BOOTSTRAP_SERVERS=localhost:9092 ./gradlew check --no-daemon` 通过

**Sprint 10 (Zone Partition Scheduling Regression Guard)** — ✅ 已完成

完成内容：
1.  ✅ **求解器封装抽象** — 新增 `LcmSolverFacade`，统一承接 `SchedulingService` / `PartitionedSchedulingService` 对 Timefold `SolverManager` 的访问，便于在 Quarkus 集成测试中稳定注入替身
2.  ✅ **Zone 分片调度回归测试** — 新增 `PartitionedSchedulingServiceTest`，覆盖“跨 Zone 选择最优解”与“所有 Zone 均不可分配时保持 `PENDING`”两类关键路径
3.  ✅ **状态持久化校验** — 回归测试显式验证分区调度命中节点后会落库 `SCHEDULED`、写入 `assignedNodeId` / `scheduledAt`，避免仅靠日志判断
4.  ✅ **文档状态对齐** — 同步勾选阶段三与阶段六中的“按 Zone 分片”条目，消除路线图与实现状态不一致的问题
5.  ✅ **测试验证** — `./scripts/check_ci_contract.sh`、`chmod +x scripts/generate_keys.sh && ./scripts/generate_keys.sh`、`cd core && ./gradlew test --tests com.sc.lcm.core.service.PartitionedSchedulingServiceTest --no-daemon` 与按 compose 对齐环境执行 `cd core && env QUARKUS_DATASOURCE_REACTIVE_URL=postgresql://localhost:5432/lcm_db QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/lcm_db QUARKUS_DATASOURCE_USERNAME=lcm_user QUARKUS_DATASOURCE_PASSWORD=lcm_password QUARKUS_REDIS_HOSTS=redis://localhost:6379 KAFKA_BOOTSTRAP_SERVERS=localhost:9092 ./gradlew check --no-daemon` 通过

**Sprint 11 (Command Dispatch Execution Modes)** — ✅ 已完成

完成内容：
1.  ✅ **作业执行策略建模** — `Job` 新增 `executionType` / `executionPayload`，支持 Docker、Shell、Ansible、SSH 四种下发模式，并通过 Flyway 迁移补齐持久化字段
2.  ✅ **Core 分发链路补强** — `JobResource` 支持接收执行模式，`SchedulingService` / `PartitionedSchedulingService` 保留执行元数据，`JobDispatcher` 按作业配置分发 `EXEC_DOCKER` / `EXEC_SHELL` / `EXEC_ANSIBLE` / `EXEC_SSH`
3.  ✅ **Satellite SSH 执行器** — 新增 `RunSSH`，通过本地 OpenSSH 客户端执行远程命令，支持私钥认证、可选 `sshpass` 密码认证以及 `known_hosts` / 非严格校验策略
4.  ✅ **回归测试补齐** — `JobDispatcherTest` 覆盖多执行模式映射；`E2EIntegrationTest` 新增 Shell 作业分发链路验证；`satellite/pkg/executor/ssh_test.go` 覆盖 SSH 执行成功、失败与参数校验
5.  ✅ **测试验证** — `cd core && ./gradlew test --tests com.sc.lcm.core.service.JobDispatcherTest --tests com.sc.lcm.core.E2EIntegrationTest --no-daemon` 通过；`docker run --rm -e GOPROXY=off -e GOMODCACHE=/go/pkg/mod -v /home/wenbk/go/pkg/mod:/go/pkg/mod -v /home/wenbk/projects/work/hyperscale-lcm:/workspace -w /workspace/satellite golang:1.24.7 go test ./... -count=1` 通过；并补跑 `./scripts/check_ci_contract.sh`、`chmod +x scripts/generate_keys.sh && ./scripts/generate_keys.sh`、按 compose 对齐环境执行 `cd core && env QUARKUS_DATASOURCE_REACTIVE_URL=postgresql://localhost:5432/lcm_db QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/lcm_db QUARKUS_DATASOURCE_USERNAME=lcm_user QUARKUS_DATASOURCE_PASSWORD=lcm_password QUARKUS_REDIS_HOSTS=redis://localhost:6379 KAFKA_BOOTSTRAP_SERVERS=localhost:9092 ./gradlew check --no-daemon` 通过

**Sprint 12 (Topology Visualization Delivery)** — ✅ 已完成

完成内容：
1.  ✅ **拓扑元数据出参补齐** — `/api/nodes` 现在返回 `gpuTopology`、`nvlinkBandwidthGbps`、`ibFabricId`，前端可直接按 GPU / NVLink / IB Fabric 维度渲染物理布局
2.  ✅ **Topology 页面重构** — 原先基于固定 8 GPU 假设的示意图改为按 Zone / Rack 分组、按真实 `gpuCount` 和作业 `requiredGpuCount` 着色的分配视图，并新增活动作业图例与 IB Fabric 总览
3.  ✅ **实时刷新补齐** — Topology 页面现已监听 `SCHEDULE_EVENT` / `JOB_STATUS` / `NODE_STATUS` / `HEARTBEAT_UPDATE`，在调度和状态变更时即时刷新布局
4.  ✅ **回归测试补齐** — 新增 `NodeResourceTest` 校验拓扑字段映射，新增 `TopologyPage.test.tsx` 覆盖 Zone / Rack / NVLink / IB Fabric 展示、Idle 过滤与 WebSocket 刷新
5.  ✅ **测试验证** — `cd core && ./gradlew test --tests com.sc.lcm.core.api.NodeResourceTest --no-daemon` 通过；`cd frontend && npm test -- src/pages/TopologyPage.test.tsx` 通过；并补跑 `cd frontend && npm test`、`cd frontend && npm run lint`、`cd frontend && npm run build`、`./scripts/check_ci_contract.sh`、`chmod +x scripts/generate_keys.sh && ./scripts/generate_keys.sh`、按 compose 对齐环境执行 `cd core && env QUARKUS_DATASOURCE_REACTIVE_URL=postgresql://localhost:5432/lcm_db QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/lcm_db QUARKUS_DATASOURCE_USERNAME=lcm_user QUARKUS_DATASOURCE_PASSWORD=lcm_password QUARKUS_REDIS_HOSTS=redis://localhost:6379 KAFKA_BOOTSTRAP_SERVERS=localhost:9092 ./gradlew check --no-daemon`

**Sprint 13 (Trace Propagation Hardening)** — ✅ 已完成

完成内容：
1.  ✅ **状态回调 trace 载荷补齐** — `JobStatusCallback` 新增 `traceContext` 字段，Satellite 回传的 `trace_context` 不再在 Kafka `jobs.status` 这一跳丢失
2.  ✅ **Core 消费端上下文恢复** — `JobExecutionService` 在处理状态回调时会从 `traceContext` 提取父上下文，并创建 `job-status-callback` consumer span，补齐 Satellite → Kafka → Core 的 trace 续接
3.  ✅ **Kafka 透传回归测试** — 新增 `JobStatusForwarderTest` 验证 Kafka payload 会保留 traceContext，新添 `JobExecutionServiceTraceContextTest` 验证 Core 能恢复 remote parent trace
4.  ✅ **E2E 断言增强** — `E2EIntegrationTest` 现在显式上报 `traceparent`，并校验 `jobs.status` 主题中的状态消息仍包含原始 traceContext
5.  ✅ **测试验证** — `cd core && ./gradlew test --tests com.sc.lcm.core.service.JobStatusForwarderTest --tests com.sc.lcm.core.service.JobExecutionServiceTraceContextTest --tests com.sc.lcm.core.E2EIntegrationTest --no-daemon` 通过；并补跑 `./scripts/check_ci_contract.sh`、`chmod +x scripts/generate_keys.sh && ./scripts/generate_keys.sh`、按 compose 对齐环境执行 `cd core && env QUARKUS_DATASOURCE_REACTIVE_URL=postgresql://localhost:5432/lcm_db QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/lcm_db QUARKUS_DATASOURCE_USERNAME=lcm_user QUARKUS_DATASOURCE_PASSWORD=lcm_password QUARKUS_REDIS_HOSTS=redis://localhost:6379 KAFKA_BOOTSTRAP_SERVERS=localhost:9092 ./gradlew check --no-daemon`

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
    *   [x] Core 覆盖率基线与 CI 门禁已落地，当前默认基线为 `30%`。
    *   [x] Satellite 注册 → 调度 → Kafka 回调 → 前端刷新的跨服务链路已建立回归保障。
    *   [x] 调度结果拓扑可视化（GPU / NVLink / IB Fabric）已落地，并具备前后端回归测试。
    *   [x] Satellite → Kafka → Core 的 OTel trace propagation 已具备显式透传与回归测试。
    *   [ ] 真实硬件 Redfish / BMC 验证仍待补齐。
