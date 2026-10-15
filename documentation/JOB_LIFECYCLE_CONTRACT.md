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

尚未实现（M2 后续批）：`UNKNOWN`（结果未知，需人工核对）、执行尝试（attempt）标识。

## 2. 转换（已实现）

回调只接受执行端的三种报告：`RUNNING`、`COMPLETED`、`FAILED`。

| 当前 \ 报告 | RUNNING | COMPLETED / FAILED | 其他（PENDING/SCHEDULED/CANCELLED） |
|---|---|---|---|
| PENDING / SCHEDULED | 应用 | 应用 | 忽略 |
| RUNNING | 忽略（重复） | 应用 | 忽略 |
| COMPLETED / FAILED / CANCELLED | 忽略 | 忽略 | 忽略 |

- 回调和调度落库在事务中先对作业加数据库行锁，再读取与判断状态；取消的条件更新使用同一数据库行锁串行化。终态不被任何回调改写；迟到的 `RUNNING`、重复或相反的终态都被忽略（warn 日志，不进 DLQ）。
- 同一条回调经 gRPC 直调和 `jobs.status` Kafka 回读各处理一次，两份副本因上表天然幂等。
- 取消后收到执行端的真实结果：状态保持 `CANCELLED`，并为该次报告写一条审计 `JOB_CANCELLED`，details 为 `late_result_after_cancel`（含上报状态与退出码），保留"其实执行过"的证据。该审计等待写入完成；重复迟到报告仍可能产生多条审计，事件身份与去重属于后续批次。
- 迁移 `PENDING → SCHEDULED` 只由调度落库完成（`JobExecutionService.persistScheduledDispatch`），不接受回调。

## 3. 取消（已实现，首版保守）

- `DELETE /api/jobs/{id}`（ADMIN/OPERATOR）：仅 `PENDING`、`SCHEDULED` 可取消，用条件更新（`status in (PENDING, SCHEDULED)`）完成，并写 `JOB_CANCELLED` 审计（操作人）。
- `RUNNING`、终态返回 **409**；不存在返回 404。**运行中取消暂不支持**：Satellite 没有取消命令，也没有"远端已停止"的证据；关闭 SSH 连接不保证远端进程终止。
- 已取消的作业不会被随后派发：调度落库发现作业已不在 `PENDING/SCHEDULED` 时不再发 Kafka；`JobDispatcher` 在发送前从库中确认仍为 `SCHEDULED`，否则丢弃。数据库检查属于返回给 Kafka 的异步链，检查失败传播为消息处理失败；不会提前确认消息。该检查与取消之间仍有极小的竞态窗口（检查通过后、命令发出前被取消）——此时作业会执行，随后结果走"取消后收到结果"路径留下审计证据。

## 4. 派发与回调的现有保证（如实记录）

- `SCHEDULED` 先落库、再经 Kafka（`jobs.scheduled`）异步派发；Kafka 发送失败、或 Satellite 无在线命令流（`StreamRegistry.sendCommand` 只记 warn）时，作业会**停在 `SCHEDULED`**，没有重试、没有超时、没有对账。属 M2 B/D 批。
- Satellite 侧没有命令确认、没有按 `command_id` 去重（重复到达会再执行一次）、状态回调失败不重试；断流期间的终态回调会丢失。属 M2 B/C 批。
- Docker 执行器 `ContainerStart` 即视为完成（不等退出、不读退出码）。属 M2 C 批。

## 5. 幂等性分类（后续重试策略的前提）

| 执行方式 | 分类 | 结果不明时 |
|---|---|---|
| SSH `SYSTEM_INFO` | 只读，可重复 | 允许操作者确认后新建尝试；仍不自动重发 |
| SSH 内联命令、SHELL、ANSIBLE、DOCKER | 视为非幂等 | **禁止自动重发**；进入可核对状态，由操作者新建尝试 |

## 6. 明确不保证

- 不声称 exactly-once；"至多一次"与"至少一次"都不保证，直到 B/C 批的持久派发与去重落地并验证。
- 超时（Satellite 端 `LCM_SSH_TIMEOUT`）只表示本端终止了 ssh 客户端，不表示远端命令已停止。
- 状态变化的通知（WebSocket）、指标、审计是尽力而为，不参与状态正确性。
