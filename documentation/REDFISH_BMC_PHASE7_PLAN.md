# 阶段七：Redfish/BMC 协议深化与硬件准入

> Updated: 2026-04-11

## Summary

- 阶段七继续定位为 `Redfish/BMC` 专项深化，不扩散到多集群联邦或新的大平台主题。
- Claude 的审核意见已吸收进最终方案；其中最关键的收敛点是：Core 统一选用 `Java 11 HttpClient` 作为 Redfish 传输底座，不再保留 “或 Quarkus REST Client” 的开放分支。
- 本阶段主线固定为：`会话认证与能力探测 -> 受控管理动作 -> 真实硬件准入矩阵 -> 失败诊断与运维可见性`。
- 本阶段既做 Core 的 session-aware 管理能力，也做 Satellite 的 session-aware 只读采集能力；不扩展到 `EventService`、firmware update、virtual media、BIOS 配置、license/OEM inventory。
- 现有 `/api/discovery/{id}/claim` 与 `/api/discovery/{id}/rotate-credentials` 保留一阶段兼容，但只作为薄转发入口；正式管理面迁移到 `/api/bmc/devices/{id}/...`，旧入口在 Phase 8 移除。

## Key Changes

- **Step 0：统一 Redfish 客户端底座**
  - Core 新增统一 `RedfishTransport + RedfishSessionManager`，基于 `java.net.http.HttpClient`，统一承接 `GET/POST/PATCH/DELETE`、TLS、Basic Auth、Session Auth、401 自动重建、能力探测和 reset action。
  - 现有 `RedfishClaimExecutor` 与 `RedfishManagedAccountProvisioner` 不再各自管理 HTTP 细节；`power-actions` 也必须复用同一底座。
  - Session 只保存在进程内；实际 TTL 取 `min(BMC SessionTimeout, lcm.claim.redfish.session-ttl-seconds-max)`；若 BMC 未返回 timeout，则使用配置上限；缓存淘汰和应用关闭时对已知 session URI 做 best-effort `DELETE`。
  - Satellite 在 Go 侧补齐对应的 session-aware 只读客户端，仅用于 inventory / telemetry 拉取，不引入写操作。
- **认证与能力模型**
  - 认证优先级固定为：`DiscoveredDevice.redfishAuthModeOverride > CredentialProfile.redfishAuthMode > lcm.claim.redfish.auth-mode-default`。
  - 支持 `BASIC_ONLY`、`SESSION_PREFERRED`、`SESSION_ONLY` 三种模式；默认值为 `SESSION_PREFERRED`。
  - `redfishAuthModeOverride` 为人工例外开关，仅用于单台异常设备；系统不会自动把临时 fallback 固化为 override。
  - `DiscoveredDevice` 增加能力与诊断快照：`lastSuccessfulAuthMode`、`lastAuthFailureCode`、`lastAuthFailureReason`、`bmcCapabilities`、`lastCapabilityProbeAt`。
  - capability 缓存失效规则固定为三类：`claim/rotate-credentials` 强制重探；capability-dependent 动作收到 `404/405/501` 时失效并重探一次；超过 `lcm.claim.redfish.capability-ttl-seconds` 自动过期。
- **BMC 管理面与受控动作**
  - 新增 `BmcManagementResource` 作为统一入口；旧 `DiscoveryResource` BMC 动作只做转发并标记弃用。
  - `power-actions` 仅支持安全子集：`On`、`ForceOff`、`GracefulShutdown`、`GracefulRestart`、`ForceRestart`。
  - 明确排除 `PushPowerButton`、`PowerCycle`、`Nmi`，原因是厂商语义不稳定或破坏性过高，不进入 Phase 7。
  - `power-actions` 请求必须带 `Idempotency-Key` header；请求体固定为 `{ "action": "...", "systemId": "optional" }`。
  - 当设备只有一个 `ComputerSystem` 成员时允许省略 `systemId`；若探测到多个成员而未提供 `systemId`，返回 `400 MULTIPLE_SYSTEMS_REQUIRE_SYSTEM_ID`，绝不猜测第一个成员。
  - `power-actions` 执行语义固定为：本地 dry-run 返回 `200 DRY_RUN`；BMC 即时完成返回 `200 COMPLETED`；BMC 返回 task/异步接受时透传为 `202 ACCEPTED` 并返回 `taskLocation`；Phase 7 不新增独立 task 轮询 API。
  - `503 + Retry-After` 只对只读请求和 session 创建做一次有界重试；破坏性 `power-actions` 不做自动重试，调用方如需重试必须复用同一个 `Idempotency-Key`。
- **审计、指标与验收资产**
  - `AuditService` / `AuditLog` 扩展 BMC 事件类型，至少覆盖：claim、rotate-credentials、power-action；详情 JSON 固定包含 actor、deviceId、systemId、action/authMode、结果、taskLocation。
  - `MetricsService` 新增至少两个指标：`lcm_bmc_power_action_total{action,result}`、`lcm_bmc_session_reauth_total{component}`。
  - `power-actions` 支持 `?dryRun=true`，返回目标 URI、解析后的 `systemId`、选用认证模式和能力判定结果，但不执行真实 BMC 变更。
  - 真实硬件验收资产固定放在 `documentation/hardware-acceptance/`：每机型一份 `<vendor>-<model>.md`，根目录维护 `matrix.yaml` 汇总固件版本、认证模式、claim/rotate/reset 结果和最后验收日期。

## Public Interfaces

- 新增 REST：
  - `GET /api/bmc/devices/{id}/capabilities`
  - `POST /api/bmc/devices/{id}/claim`
  - `POST /api/bmc/devices/{id}/rotate-credentials`
  - `POST /api/bmc/devices/{id}/power-actions?dryRun=true|false`
- 兼容 REST：
  - 保留 `POST /api/discovery/{id}/claim`
  - 保留 `POST /api/discovery/{id}/rotate-credentials`
  - 两者仅转发到统一服务实现，并标注 `@Deprecated`；Phase 8 删除
- `power-actions` 契约：
  - 请求体固定为 `{ "action": "...", "systemId": "optional" }`
  - Header 必须携带 `Idempotency-Key`
  - 响应允许 `200 DRY_RUN`、`200 COMPLETED`、`202 ACCEPTED + taskLocation`
- 新增/扩展字段：
  - `CredentialProfile.redfishAuthMode`
  - `DiscoveredDevice.redfishAuthModeOverride`
  - `DiscoveredDevice.lastSuccessfulAuthMode`
  - `DiscoveredDevice.lastAuthFailureCode`
  - `DiscoveredDevice.lastAuthFailureReason`
  - `DiscoveredDevice.bmcCapabilities`
  - `DiscoveredDevice.lastCapabilityProbeAt`
- 新增配置：
  - `lcm.claim.redfish.auth-mode-default=SESSION_PREFERRED`
  - `lcm.claim.redfish.session-ttl-seconds-max=1800`
  - `lcm.claim.redfish.capability-ttl-seconds=900`
  - `lcm.claim.redfish.retry-after-max-seconds=5`
  - `LCM_BMC_AUTH_MODE=SESSION_PREFERRED`
- 明确不变：
  - 不修改现有 gRPC `lcm.proto`
  - 不新增 `EventService`、firmware、virtual media、BIOS、license/OEM inventory 接口

## Test Plan

- **Core transport / session**
  - `SESSION_PREFERRED` 在支持 `SessionService` 的 profile 上优先走 session。
  - `SESSION_PREFERRED` 在不支持 session、或 session 创建成功但无 `X-Auth-Token` 时回退到 Basic。
  - `SESSION_ONLY` 命中不支持 session 的设备时返回明确失败码，并写回诊断字段。
  - session 中途 `401` 时自动重建并重放一次只读请求；并发请求共享 session 时不产生重复建会话风暴。
  - `503 + Retry-After` 在只读请求和 session 创建上按配置做一次有界重试。
- **Capability / management**
  - claim 与 rotate-credentials 在 capability stale、`404/405/501`、超时、`401/403` 下都能输出明确失败原因。
  - `power-actions` 覆盖单系统省略 `systemId`、多系统缺失 `systemId`、dry-run、同步 `200`、异步 `202 + taskLocation`、重复 `Idempotency-Key` 重放、无 capability 拒绝执行。
  - 审计日志记录 actor/device/action/result，指标按 result 正确累加。
- **Satellite**
  - 现有四个 vendor fixture 继续通过。
  - 新增 session-capable fixture 变体后，Satellite 在 `BASIC_ONLY` 与 `SESSION_PREFERRED` 下均能采集型号、电源、温度。
  - Satellite 本阶段只验证只读 session 生命周期，不验证写操作。
- **Mock / demo / hardware acceptance**
  - 扩展 `RedfishMockServer` 与 demo mock：支持 `SessionService/Sessions`、session 失效、缺 token、task URI、capability 开关、多系统集合。
  - 真实硬件准入至少形成一套 `matrix.yaml + 单机型 markdown` 样板；若有样机，优先验证 `OpenBMC + 一种商业 BMC`。
  - destructive power action 的真实验收只允许在维护窗口或专用样机执行；默认验收先覆盖 claim、rotate、capability probe。
- 本次文档收敛只做计划整合，不涉及代码行为变更。

## Implementation Notes (2026-04-14)

- **测试闭环**: 新增 `core/src/test/java/com/sc/lcm/core/api/BmcManagementResourceTest.java`（5 个用例）覆盖 `GET /capabilities` 快照读取（OPERATOR 可读）、OPERATOR 对 `claim / rotate / power-actions` 的 `403` 拒绝、`claim` 返回 `CLAIMED/AUTHENTICATED` 设备、`rotate-credentials` 在 `SKIPPED` 路径下返回 `304`、`power-actions` 返回 `202 + Location + taskLocation` 并校验 `MetricsService.recordBmcPowerAction` 被触发。测试使用 `@InjectMock` 隔离 `BmcClaimWorkflowService / BmcCredentialRotationService / RedfishPowerActionService / AuditService / MetricsService`，通过 JDBC 直接写入 `discovered_devices` 夹具以避免 reactive flush 竞态。
- **前端管理面接入**: `frontend/src/pages/DiscoveryPage.tsx` 及 `DiscoveryApprovalPanel` 接入 Phase 7 管理面：能力快照面板、凭据轮换、`power-actions` 下拉与 `dry-run` 预演、ADMIN 角色强校验与二次确认；`frontend/src/api/client.ts` 新增 `fetchBmcCapabilities / executeBmcClaim / rotateBmcCredentials / executeBmcPowerAction`，旧 `executeDiscoveryClaim` 薄转发到 `/api/bmc/devices/{id}/claim`；`DiscoveryPage.test.tsx` 补齐 “BMC power execution locked behind ADMIN” 回归。
- **E2E 与 JaCoCo 阻塞点收口**: `JobDispatcher.resolveTargetNodeId` 优先使用 `assignedNodeId`，避免解绑后 dispatch 取空；`PartitionedSchedulingService` / `SchedulingService` 在发送调度消息前显式置空 `assignedNode` 并只写 `assignedNodeId`，消除 Hibernate Reactive 下的 transient 关联；`LcmGrpcService` 在 gRPC 状态回调路径上直接调用 `JobExecutionService.processJobStatusCallback`，该方法对 `(status, nodeId, exitCode, errorMessage, completedAt)` 做幂等短路，避免与 Kafka 路径重复落库；`core/build.gradle` 将 JaCoCo 输出统一到 `jacoco-quarkus.exec`，消除与 Quarkus 双重插桩的 merge 冲突。合入后 `./gradlew cleanTest check --no-daemon` 在清洁环境下 158/158 通过（含 `E2EIntegrationTest` 2/2）。
- **开发期环境提示**: 本地如同时运行 `quarkusDev` 与测试 JVM，会共享 `lcm-core-group` Kafka 消费组并互抢 `jobs.scheduled` 消息，导致 `E2EIntegrationTest` 偶发 `TimeoutException`。复现 E2E 前务必停掉 `quarkusDev` 与 `demo-satellite` 容器并清理 `jobs / satellites` 残留行。
- **待办不变**: Satellite session-aware 只读客户端仍按计划延后到 Phase 8；真实硬件准入矩阵仍等待实验台数据填入 `documentation/hardware-acceptance/matrix.yaml`。

## Implementation Notes (2026-04-11)

- Core 部分按计划全部落地：`RedfishTransport + RedfishSessionManager` 已统一承接 GET/POST/PATCH/DELETE 与 401 自动重建；`BmcManagementResource` 上线 `/api/bmc/devices/{id}/{capabilities,claim,rotate-credentials,power-actions}`；旧 `/api/discovery/{id}/claim` 与 `/api/discovery/{id}/rotate-credentials` 标记 `@Deprecated(forRemoval=true)`，仅做薄转发。
- 受控电源动作严格收敛到白名单 `On / ForceOff / GracefulShutdown / GracefulRestart / ForceRestart`，必须携带 `Idempotency-Key`，多 ComputerSystem 时显式 `systemId`，破坏性动作不自动重试。
- 审计与指标已落地：`AuditLog.AuditEventType` 新增 `BMC_CLAIM / BMC_ROTATE_CREDENTIALS / BMC_POWER_ACTION`；`MetricsService` 暴露 `lcm_bmc_power_action_total{action,result}` 与 `lcm_bmc_session_reauth_total{component}`。
- Satellite 的 session-aware 只读客户端**延后到 Phase 8**：本阶段不改 `satellite/pkg/redfish/`，避免 Go 侧 HTTP client 重构与 fixture 调整阻塞 Core 主线。文档收敛 + Core 端 `RedfishMockServer.withSessionService()` 已经能完整覆盖 session 协议路径，Satellite 仍然以 Basic 拉取，待 Phase 8 单独处理。
- 真实硬件验收资产骨架放在 `documentation/hardware-acceptance/`：`README.md` 描述更新规则、`matrix.yaml` 是机器可读汇总、`openbmc-reference.md` 是单机型样板。首批真实数据待硬件实验台就绪后填入。

## Assumptions

- Phase 7 不做额外审批流；破坏性 `power-actions` 直接限制为 `ADMIN` 角色，`OPERATOR` 只能看能力，不执行动作。
- `dryRun=true` 只是安全预演，不改变 RBAC，也不写真实 BMC。
- 真实硬件池可能仍不完整，因此代码、仿真、文档矩阵可以先落地；但 roadmap 上“真实硬件 Redfish/BMC 验证”仍保持独立验收项。
- OEM 差异优先通过模板与 capability profile 处理；只有标准路径无法覆盖时才允许最小 OEM hook。
