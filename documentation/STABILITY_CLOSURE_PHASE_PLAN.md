# Phase Stability Closure：把断连恢复从"代码里有"收口为"测试里证"

> Updated: 2026-06-12
> Status: **Landed** — 见文末落地记录。
> 由 Claude Code (Fable 5) 编写。
> 约束前提：与前两阶段一致——无真实 BMC / 裸机 / 真实 AlertManager secret；不依赖外部条件。

## Summary

- [PROJECT_STATUS.md](PROJECT_STATUS.md) §1.1 长期承认"断连恢复和压力级故障场景仍不充分"。Deployment Closure 已把重启恢复写进 [deployment.md §5](runbooks/deployment.md) 的人工验收 checklist，本阶段负责把**可自动化的断连恢复行为固化进测试套件**。
- 现状证据（2026-06-12 梳理）：
  1. **Satellite 命令流重连循环完全无测试且不可测**：重连逻辑（连接 → init 握手 → recv 循环 → 断开后 5s 重试 → ctx 取消退出）以匿名 goroutine 内联在 [main.go:121-176](../satellite/cmd/satellite/main.go)，无法被任何单测触达。这是整个"断连恢复"链路里唯一零覆盖的关键路径。
  2. **DLQ 路由已有单测、缺真实 Kafka 级证明**：[JobExecutionServiceTest](../core/src/test/java/com/sc/lcm/core/service/JobExecutionServiceTest.java) 已用 mock Emitter 覆盖"非法 JSON → DLQ"与"非法 status → DLQ"两条路径，但没有任何测试证明消息真的会从 `jobs.status` 流到 `jobs.status.dlq` topic（连接器配置、topic 名、序列化器均未被集成级验证）。
  3. **`jobs.status.dlq` 没有消费者**：DLQ 只有发送侧，"回放"机制不存在——那是新功能开发，不属于"把已有行为固化进测试"的本阶段定位。
- Phase 定位：**小步可验证的测试收口**，两个工作项，不新增任何运行时能力。

## Key Changes

### Step 0 — 基线确认（本文件）

固化上述三条现状证据与阶段边界。

**commit**：`docs: add Phase Stability Closure plan`

### Step 1 — Satellite：提取命令流重连循环为可测包 + bufconn 单测

**重构**（行为保持，见下方唯一例外）：

- 新建 `satellite/pkg/stream/loop.go`：`RunCommandStreamLoop(ctx, client, cfg, handler)`，把 main.go 匿名 goroutine 的逻辑原样迁移——连接失败重试、init 握手失败重试、recv 错误断流重连、ctx 取消即退出、重试间隔默认 5s（可配置，便于测试用短间隔）。
- [main.go](../satellite/cmd/satellite/main.go) 的 goroutine 体替换为对新函数的调用，`handleCommand` 经闭包注入，签名与依赖不变。
- **唯一行为差异（显式声明）**：重试等待从 `time.Sleep` 改为 ctx 感知的 select——ctx 取消时立即退出而不是最多再等 5s。这是优雅关闭的严格改进，也是测试能快速收敛的前提。

**新测试** `satellite/pkg/stream/loop_test.go`，用 bufconn + 进程内 gRPC server 模拟 Core：

1. **断流重连**：server 收到 init 握手后下发一条命令并主动断流；断言 client 重连并完成第二次握手、命令被 handler 处理。
2. **连接失败重试**：dialer 始终失败；断言循环按重试间隔持续尝试而不崩溃，ctx 取消后退出。
3. **ctx 取消干净退出**：server 保持流打开不发数据；取消 ctx，断言函数在限定时间内返回。

### Step 2 — Core：DLQ 路由的真实 Kafka 集成断言

**新测试**：[E2EIntegrationTest](../core/src/test/java/com/sc/lcm/core/E2EIntegrationTest.java) 增加一个用例——用 KafkaCompanion 向 `jobs.status` 生产一条带唯一 marker 的非法 JSON，消费 `jobs.status.dlq` 断言同一 payload 在限定时间内到达。证明 connector 配置、topic 名与序列化链路端到端成立。

不改 `JobExecutionService` 生产代码；不动既有 E2E 用例。

### Step 3 — 文档同步

落地后回写 [PROJECT_STATUS.md](PROJECT_STATUS.md)（§1.1 集成测试行的"断连恢复…仍不充分"措辞按事实收窄）与 [DEVELOPMENT_ROADMAP.md](../DEVELOPMENT_ROADMAP.md) Current Focus；本文件补落地记录。

## Public Interfaces

- 不新增/修改任何 REST、gRPC、Kafka 契约；`lcm.proto` 不动。
- 新增 Go 包 `satellite/pkg/stream`（内部重构产物，不是对外 API 承诺）。
- Satellite 运行时行为除"关闭时不再多等一个重试间隔"外完全不变。

## Test Plan

| 层 | 命令 | 新增 / 存量 |
|---|---|---|
| Satellite | `cd satellite && go test ./... -count=1`（新增 3 个重连场景用例） | 新增 + 存量回归 |
| Core | `cd core && ./gradlew check --no-daemon`（新增 DLQ E2E 用例，需 Kafka） | 新增 + 存量回归 |
| Frontend | `cd frontend && npm test && npm run lint && npm run build`（未触碰，惯例回归） | 存量回归 |
| CI contract guard | `./scripts/check_ci_contract.sh`（`core/src/test/**` 属高风险路径） | 存量 |
| GitHub Actions | push 后全流水线绿 | 存量门禁 |

## 不做的事

- 不做 CI 级 Core/Kafka 进程重启或网络分区注入（CI 不可靠、flaky 风险高；三级重启恢复由 [deployment.md §5](runbooks/deployment.md) 人工 checklist 承接）
- 不实现 DLQ 回放/消费机制（新功能，需要独立立项与产品口径）
- 不调整 load-test 规模、阈值或 readiness 超时
- 不动 heartbeat 主循环（行为是"失败仅记日志、下个周期重试"，由 loadgen 与 demo-smoke 间接覆盖）
- 不新增 CI job

## Assumptions

- bufconn 进程内 gRPC server 足以真实复现 stream 断开语义（grpc-go 标准测试手法）。
- E2EIntegrationTest 的 KafkaCompanion 基建可直接复用于 DLQ topic 消费。
- 本阶段不阻塞 Deployment Closure 的 Step 5 验收走查（两者独立，后者等外部环境）。

## 落地记录（2026-06-12）

- **commit 序列**：`e0c7cbe`（本主稿）、`f722dbe`（satellite `pkg/stream` 提取 + 3 个 bufconn 回归用例：断流重连重握手、连接失败持续重试、ctx 取消即时退出）、`3e94056`（Core E2E DLQ 真实 broker 断言）、`768cdb1`（顺手修复 CI 暴露的 SatelliteTable 既有测试竞态 flake——断言改 `findByText` 等待 fetch 后重渲染）。
- **验证**：satellite 全套件绿（6 包）；core `gradlew check` 绿（159 测试，新增 DLQ 用例在真实 Kafka 上通过）；frontend 29/29 + lint + build；CI contract guard 通过；main CI run `27407588407` 全绿（首推 run `27407153946` 因上述既有 frontend flake 翻红，已修复）。
- **行为差异声明**：satellite 重连重试等待改为 ctx 感知，优雅关闭不再额外等待最多一个重试间隔；其余运行时行为不变。
