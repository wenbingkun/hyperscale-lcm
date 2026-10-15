# 作业生命周期契约（M2）

本文是作业状态、派发、回调与取消语义的事实源；实现与测试以它为准，变更须同步本文。范围与批次见 [NO_HARDWARE_PHASE_PLAN.md](NO_HARDWARE_PHASE_PLAN.md) M2。**已实现**与**尚未实现**分开标注，不把计划当现状。

## 1. 状态

| 状态 | 含义 | 终态 |
|---|---|---|
| `PENDING` | 已提交，尚未选定执行端 | 否 |
| `SCHEDULED` | 已选定执行端并落库；命令是否已送达执行端**未保证** | 否 |
| `RUNNING` | 执行端报告已开始执行 | 否 |
| `COMPLETED` | 执行端报告成功（`exitCode`/输出为执行端上报值） | 是 |
| `FAILED` | 执行端报告失败，或 Core 在派发前拒绝（如 SSH 目标变化） | 是 |
| `CANCELLED` | 在开始执行前被取消 | 是 |
| `UNKNOWN` | 命令已交给执行端的命令流，但超时仍无结果；**可能执行过**，需人工核对 | 否（可被迟到的真实结果或人工核对收口） |

尚未实现（M2 后续批）：执行尝试（attempt）在协议里的标识（Core 侧 `dispatch_attempt` 计数已有）。

## 2. 转换（已实现）

回调只接受执行端的三种报告：`RUNNING`、`COMPLETED`、`FAILED`。

| 当前 \ 报告 | RUNNING | COMPLETED / FAILED | 其他（PENDING/SCHEDULED/CANCELLED） |
|---|---|---|---|
| PENDING / SCHEDULED | 应用 | 应用 | 忽略 |
| RUNNING | 忽略（重复） | 应用 | 忽略 |
| UNKNOWN | 应用 | 应用 | 忽略 |
| COMPLETED / FAILED / CANCELLED | 忽略 | 忽略 | 忽略 |

- 回调和调度落库在事务中先对作业加数据库行锁，再读取与判断状态；取消的条件更新使用同一数据库行锁串行化。终态不被任何回调改写；迟到的 `RUNNING`、重复或相反的终态都被忽略（warn 日志，不进 DLQ）。
- 同一条回调经 gRPC 直调和 `jobs.status` Kafka 回读各处理一次，两份副本因上表天然幂等。
- 取消后收到执行端的真实结果：状态保持 `CANCELLED`，并为该次报告写一条审计 `JOB_CANCELLED`，details 为 `late_result_after_cancel`（含上报状态与退出码），保留"其实执行过"的证据。该审计等待写入完成；重复迟到报告仍可能产生多条审计，事件身份与去重属于后续批次。
- 迁移 `PENDING → SCHEDULED` 只由调度落库完成（`JobExecutionService.persistScheduledDispatch`），不接受回调。

## 3. 取消（已实现，首版保守）

- `DELETE /api/jobs/{id}`（ADMIN/OPERATOR）：仅 `PENDING`、`SCHEDULED` 可取消，用条件更新（`status in (PENDING, SCHEDULED)`）完成，并写 `JOB_CANCELLED` 审计（操作人）。
- `RUNNING`、终态返回 **409**；不存在返回 404。**运行中取消暂不支持**：Satellite 没有取消命令，也没有"远端已停止"的证据；关闭 SSH 连接不保证远端进程终止。
- 已取消的作业不会被随后派发：调度落库发现作业已不在 `PENDING/SCHEDULED` 时不再发 Kafka；`JobDispatcher` 在发送前从库中确认仍为 `SCHEDULED`，否则丢弃。数据库检查属于返回给 Kafka 的异步链，检查失败传播为消息处理失败；不会提前确认消息。该检查与取消之间仍有极小的竞态窗口（检查通过后、命令发出前被取消）——此时作业会执行，随后结果走"取消后收到结果"路径留下审计证据。

## 4. 派发、送达判定与扫描器（已实现，批 B）

- **认领**：向命令流发送前必须先赢得认领（`JobDispatchStore.claim`：条件更新 `dispatched_at=now, dispatch_attempt+1 where status=SCHEDULED and dispatched_at is null`）。Kafka 消费者与扫描器走同一入口，同一作业不会被 Core 双发。目标 Satellite 没有在线命令流时不认领；认领后 `sendCommand` 失败则释放认领。`dispatched_at` 非空 = "命令已交给命令流"（**不等于执行端已收到或已开始**）。
- **扫描器**（`JobDispatchSweeper`，`lcm.jobs.sweeper.interval`，默认 15s）：
  1. **未送达**（`SCHEDULED` 且 `dispatched_at` 为空）：超过 `lcm.jobs.redeliver-grace`（默认 30s）后由扫描器重新走派发入口补发（含 SSH 目标复核）；超过 `lcm.jobs.dispatch-timeout`（默认 10m）仍未送达 → `FAILED`。因为从未发出命令，可明确判定**没有执行**。
  2. **已送达无进展**：`SCHEDULED` 且 `dispatched_at` 超过 `lcm.jobs.start-timeout`（默认 5m），或 `RUNNING` 且 `coalesce(dispatched_at, scheduled_at)` 超过 `lcm.jobs.run-timeout`（默认 2h） → `UNKNOWN`。**不自动重发**。
- 所有状态更新都是条件更新（只在期望的旧状态下生效），与回调、取消并发时不会互相覆盖。
- **人工核对**：`POST /api/jobs/{id}/resolve`（ADMIN/OPERATOR），body `{"outcome":"COMPLETED|FAILED","note":"..."}`，仅 `UNKNOWN` 有效，写 `JOB_RESOLVED` 审计（操作人、结论、备注）。重新执行 = 以新作业重新提交。`UNKNOWN` 不可取消。
- **仍然成立的缺口**（批 C/D）：认领成功后、Satellite 实际收到前断流 → 命令丢失，作业在 start-timeout 后变 `UNKNOWN`（保守）；Satellite 无命令确认、无按 `command_id` 去重（Core 侧单次认领不覆盖 Satellite 侧重复到达）、状态回调失败不重试，断流期间的终态回调会丢失；Docker 执行器 `ContainerStart` 即视为完成；Core/Satellite 重启后的专门对账未做（目前只靠上述超时）。

## 5. 幂等性分类（后续重试策略的前提）

| 执行方式 | 分类 | 结果不明时 |
|---|---|---|
| SSH `SYSTEM_INFO` | 只读，可重复 | 允许操作者确认后新建尝试；仍不自动重发 |
| SSH 内联命令、SHELL、ANSIBLE、DOCKER | 视为非幂等 | **禁止自动重发**；进入可核对状态，由操作者新建尝试 |

## 6. 明确不保证

- 不声称 exactly-once。Core 侧对单个作业最多认领一次；Satellite 侧去重与回调补报（批 C）落地前，不保证"至多一次执行"。
- 超时（Satellite 端 `LCM_SSH_TIMEOUT`）只表示本端终止了 ssh 客户端，不表示远端命令已停止。
- 状态变化的通知（WebSocket）、指标、审计是尽力而为，不参与状态正确性。
