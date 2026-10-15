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
| `UNKNOWN` | 派发或执行结果无法确认（包括旧版本遗留作业）；**可能执行过**，需人工核对 | 否（可被迟到的真实结果或人工核对收口） |

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
- 其他终态作业（人工核对、扫描器判定后）收到与当前状态**冲突**的终态报告：状态不变，写 `JOB_LATE_RESULT` 审计，action 为 `late_result_ignored`（当前状态、上报状态、退出码）；人工结论一经记录即为终态，冲突证据留在审计供复查。相同状态的重复报告不写审计。
- 迁移 `PENDING → SCHEDULED` 只由调度落库完成（`JobExecutionService.persistScheduledDispatch`），不接受回调。

## 3. 取消（已实现，首版保守）

- `DELETE /api/jobs/{id}`（ADMIN/OPERATOR）：仅尚未认领（`dispatched_at is null`）的 `PENDING`、`SCHEDULED` 可取消，用条件更新（`status in (PENDING, SCHEDULED) and dispatched_at is null`）完成，并写 `JOB_CANCELLED` 审计（操作人）。
- 已认领的 `SCHEDULED`、`RUNNING`、`UNKNOWN` 及终态返回 **409**；不存在返回 404。**运行中取消暂不支持**：Satellite 没有取消命令，也没有"远端已停止"的证据；关闭 SSH 连接不保证远端进程终止。
- 取消与派发认领竞争同一数据库条件更新：取消先成功则认领失败；认领先成功则取消返回 409。因此不会在成功取消后继续发出命令。目标复核仅在认领成功后进行，重复消息不能对运行中的作业追加“目标变化”失败。数据库检查属于返回给 Kafka 的异步链，检查失败传播为消息处理失败，不提前确认消息。

## 4. 派发、送达判定与扫描器（已实现，批 B）

- **认领**：目标 Satellite 有在线命令流后，先赢得认领（`JobDispatchStore.claim`：条件更新 `dispatched_at=now, dispatch_attempt+1 where status=SCHEDULED and dispatched_at is null`），再复核目标和尝试发送。Kafka 与扫描器共用入口；重复消息不执行复核/发送副作用。`dispatched_at` 只是持久认领标记，**不证明已经发送、送达或开始执行**。只有复核报错（尚未尝试发送）或发送前已找不到 emitter，才释放认领供重试；`emit` 一旦被调用后发生异常，保留认领并记为 `UNKNOWN`、写 `JOB_UNKNOWN` 审计，绝不自动重发。执行端回调已先推进的状态保持不变。
- **扫描器**（`JobDispatchSweeper`，`lcm.jobs.sweeper.interval`，默认 15s）：
  1. **确定未发送**（本版本创建或调度的 `SCHEDULED` 且 `dispatched_at` 为空）：超过 `lcm.jobs.redeliver-grace`（默认 30s）后由扫描器重新走派发入口补发（含 SSH 目标复核）；超过 `lcm.jobs.dispatch-timeout`（默认 10m）仍未送达 → `FAILED`。因为从未发出命令，可明确判定**没有执行**。
  2. **已认领无进展**：`SCHEDULED` 且 `dispatched_at` 超过 `lcm.jobs.start-timeout`（默认 5m），或 `RUNNING` 且 `coalesce(dispatched_at, scheduled_at)` 超过 `lcm.jobs.run-timeout`（默认 2h） → `UNKNOWN`。**不自动重发**。
- 扫描先找候选，真正更新时再次检查状态、派发标记和截止时间；并发刷新、释放或回调后不再符合截止条件的作业不会被旧扫描误判。
- **人工核对**：`POST /api/jobs/{id}/resolve`（ADMIN/OPERATOR），body `{"outcome":"COMPLETED|FAILED","note":"..."}`，仅 `UNKNOWN` 有效，写 `JOB_RESOLVED` 审计（操作人、结论、备注）。重新执行 = 以新作业重新提交。`UNKNOWN` 不可取消。
- **Satellite 执行端（批 C，已实现）**：Docker 作业等待容器退出，`COMPLETED` 表示退出码 0，非 0 为 `FAILED` 并带真实退出码；输出为合并的 stdout/stderr，保留尾部 64KiB；超时（`LCM_DOCKER_TIMEOUT`，默认 1h）后请求停止容器，停止成功才报"container stopped"，否则报"state unknown"；容器无论结果都被删除。Docker payload 为镜像名，或 `{"image":"...","cmd":[...]}`。Satellite 按 `command_id` 在**进程内**记账（上限 1024）：重复到达的命令不再执行，已完成的重发其终态；终态发送失败时进入有界待补报队列（上限 256），流重连后重发（尽力而为，丢弃最旧）。命令仍在 Recv 循环中串行执行，长容器会占用命令通道。
- **仍然成立的缺口**（批 D 及之后）：认领成功后、Satellite 实际收到前断流 → 命令可能丢失，作业在 start-timeout 后变 `UNKNOWN`（保守）；Satellite 无命令确认、无 attempt id；Satellite **重启后**去重账本和待补报队列都会清空，重启后的重复投递仍可能重复执行，未送出的终态丢失（Core 靠超时变 `UNKNOWN`）；Shell/Ansible 仍无超时与输出上限；Core/Satellite 重启后的专门对账未做（目前只靠上述超时）。

### 4.1 调度与容量边界（M3）

- **调度对象**：Satellite 执行容量（CPU/内存/GPU 数量，来自 Satellite 注册上报的规格）。SSH 任务不承诺目标设备的 GPU 分配；资源数值仅约束执行它的 Satellite。
- **占用**：由作业行推导，没有独立台账——`SCHEDULED`、`RUNNING`、`UNKNOWN` 的作业占用其 `assigned_node_id` 的容量（`UNKNOWN` 命令可能仍在运行，所以占用到 resolve 为止）。完成、失败、取消、resolve 自动释放；Core 重启后占用从数据库恢复。规格也在启动时从 `node` 表恢复。
- **原子预留**：`PENDING → SCHEDULED` 时在事务里锁定目标 Satellite 行，再校验“节点容量 − 已占用”是否满足作业（CPU、内存、GPU 数量、GPU 型号、NVLink 与带宽）。不满足则作业保持 `PENDING`，不入队、不派发。并发请求因行锁串行，8 核节点同时收到两个 8 核请求只会放行一个。
- **无可行节点**：作业保持 `PENDING`；sweeper 每 `lcm.jobs.pending-retry-interval`（30s）重新触发调度（容量释放、Satellite 上线、Core 重启丢失的调度都靠它恢复）；超过 `lcm.jobs.pending-timeout`（10m）置 `FAILED`，`errorMessage` 说明从未派发、没有任何命令执行，并写 `JOB_FAILED` 审计。
- **未支持的条件**：GPU 型号是硬约束（节点型号不一致则不放行）；`nodeSelector` 不是 `POST /api/jobs` 的字段，也不参与调度。Allocation API（`POST /api/v1/allocations`）会进入默认 Docker 执行路径（`hello-world`），因此默认关闭（`lcm.allocations.enabled=false`，返回 501）。
- **租户**：令牌带 `tenant_id` 声明时，提交以声明为准，请求体里不一致的 `tenantId` 返回 403；非 ADMIN 只能列出/读取本租户作业（其他租户的 id 返回 404）。令牌不带声明时保持旧行为（dev 登录的声明来自请求体，生产身份来源属于 M4）。
- **已知限制**：分区调度（`lcm.scheduling.partitioned.enabled`，默认关）求解时不扣减占用，但落库时仍会拒绝放不下的放置；单 Core 假设下行锁已足够，多 Core 同样成立（锁在数据库）。

## 5. 幂等性分类（后续重试策略的前提）

| 执行方式 | 分类 | 结果不明时 |
|---|---|---|
| SSH `SYSTEM_INFO` | 只读，可重复 | 允许操作者确认后新建尝试；仍不自动重发 |
| SSH 内联命令、SHELL、ANSIBLE、DOCKER | 视为非幂等 | **禁止自动重发**；进入可核对状态，由操作者新建尝试 |

## 6. 明确不保证

- 不声称 exactly-once。Core 对同一作业最多持有一个未释放认领；确定未尝试发送时可以释放再认领，所以 `dispatch_attempt` 可能大于 1。发送后或发送结果不确定时不自动重发；Satellite 端的去重只在进程内，Satellite 重启后不保证“至多一次执行”。
- 超时（Satellite 端 `LCM_SSH_TIMEOUT`）只表示本端终止了 ssh 客户端，不表示远端命令已停止。
- 状态变化的通知（WebSocket）、指标、审计是尽力而为，不参与状态正确性。


## 7. 升级与回退边界

- V2.10.0 将旧版本遗留的 SCHEDULED/RUNNING 作业转为 UNKNOWN，并注明升级前没有派发记录。旧作业的 dispatched_at 为空不能证明未执行，禁止把它们当作未发送作业自动补发或判定“从未执行”。
- 升级前冻结提交、停止旧 Core、备份数据库；不与旧版 Core 混跑。旧活动作业需核对目标后 resolve，或等待迟到结果；不要批量重置为 SCHEDULED。
- 旧版不认识 UNKNOWN。回退二进制前先冻结提交并核对所有 UNKNOWN，停止新 Core 后再次确认没有 UNKNOWN 才启动旧版；或按备份恢复方案处理外部副作用。保留新列即可，不需要删列。操作顺序见 [deployment.md §3.1](runbooks/deployment.md#31-前置条件) 和 [upgrade-and-backup.md](runbooks/upgrade-and-backup.md)。
