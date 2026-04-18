# Phase Software Closure：无真实设备条件下的近期收口计划

> Updated: 2026-04-18 (Round 2 doc deliverables landed: PXE runbook + recent 5 green load-test baselines)
> Status: **Approved** — 经 Round 2 + Round 3 评审定稿；作为 Software Closure 阶段实施的核心指导文件。
> 由 Codex 编写，由 Claude Opus 4.7 审核。
> 约束前提：当前无真实 BMC / 裸机设备、无真实 AlertManager secret；在真实设备与真实 secret 到位前，本计划以"软件收口"替代"真实环境验收"。

## Summary

- Phase Software Closure 专注于在"真实设备与真实告警通道暂不可用"的前提下，为 Hyperscale LCM 提供一份**定稿的近期实施指导**；它不替代 [PROJECT_STATUS.md](PROJECT_STATUS.md) 的滚动现状角色，也不替代 [DEVELOPMENT_ROADMAP.md](../DEVELOPMENT_ROADMAP.md) 的阶段历史角色。
- 当前状态梳理（2026-04-18）：
  1. [PROJECT_STATUS.md](PROJECT_STATUS.md) 仍将"真实硬件 Redfish / BMC 验收数据填充"与"AlertManager 真实 channel 接入与冒烟"列为近期优先事项，但这两条都依赖当前不可用的外部条件：真实 BMC / 裸机设备与真实 Slack / PagerDuty / 邮件 secret。
  2. AlertManager 代码、Helm values 参数化、Secret 模板、CI guard 与 [runbook](runbooks/alertmanager.md) 已经就绪；当前缺口不是"软件路径不存在"，而是"真实 secret 不可用，无法做真实送达验证"。
  3. `PXE / iPXE` 与 `load-test` 仍然存在可在纯软件条件下继续收口的剩余工作：前者偏向生产硬化准备与运维文档，后者偏向从"有门槛"走向"可比较"。
  4. Redfish / BMC 侧已具备 mock fixture、session-aware transport、`documentation/hardware-acceptance/` 骨架与 `pending:` 追踪；在真实设备缺席期间，继续扩 readiness 资产比继续宣称"近期做真实验收"更符合实际。
- Phase 定位：**先把近期主线从"真实环境验收"切到"软件收口 + readiness 保温"**。这里的"readiness 保温"特指：保持 mock / fixture / 文档资产齐备，待真实设备与真实 secret 到位后可以直接切换到正式验收。
- **定稿同步（2026-04-18）**：本次 commit 已按 [Step 5](#step-5--文档同步节奏定稿后再回写现状与路线图) 的同步节奏一次性更新 [PROJECT_STATUS.md](PROJECT_STATUS.md) 的"已知缺口与下阶段重点"顺序与 [DEVELOPMENT_ROADMAP.md](../DEVELOPMENT_ROADMAP.md) 的 `Current Focus`；[README.md](../README.md) 按原约定不变。
- 明确**不做**：不新建带日期后缀的分析文档；不因为没有真实设备而顺势启动多集群联邦开发；不把 AlertManager 写成"真实送达已验证"；不把 BMC readiness 写成"真实机型已准入"。

## Key Changes

以下按讨论与后续实施顺序组织。Round 1（主稿建立）与 Step 5 同步回写均已在本次定稿 commit 中完成；本节作为 Round 2 实施的指导蓝图继续生效。

### Step 0 — 基线确认（纯文档，已完成）

**目标**：用一份独立主稿固定当前约束与边界，避免后续讨论散落在多个文档中。

- 新建 [SOFTWARE_CLOSURE_PHASE_PLAN.md](SOFTWARE_CLOSURE_PHASE_PLAN.md) 作为本阶段唯一主稿。
- 明确写死本阶段的三个前提：无真实设备、无真实 AlertManager secret、近期目标是降低未来接入风险而非扩展新功能。

### Step 1 — AlertManager：从"真实通道验证"切到"部署与本地路由就绪"

**目标**：在无真实 secret 的前提下，把 AlertManager 定位从"近期必须完成真实送达验证"调整为"部署路径、模板渲染、路由冒烟已经可评审"。

- 继续以 [ALERTMANAGER_PHASE_PLAN.md](ALERTMANAGER_PHASE_PLAN.md) 和 [runbooks/alertmanager.md](runbooks/alertmanager.md) 为事实基础，不另起一套 AlertManager 方案。
- 本阶段只承认"chart / config / local route / runbook 已就绪"，不承认"Slack / PagerDuty / 邮件真实送达已完成"。
- 未来回写 [PROJECT_STATUS.md](PROJECT_STATUS.md) 时，措辞统一为"代码与路由已就绪，真实通道送达待 secret 注入"。
- 不顺手调整 Prometheus 规则阈值、不合并规则源、不引入 External Secrets Operator / Vault / SOPS。
- External Secrets / Vault / SOPS 的选型**延后到真实 secret 可用前再决策**；当前 chart-managed / external 双模式已经足够承接近期评审与后续实现。

### Step 2 — PXE / iPXE：聚焦单一路径的生产硬化准备

**目标**：在没有真实裸机节点的情况下，把 PXE 从"代码闭环存在"推进到"真实环境一到位即可按补齐后的 runbook 执行"。

- 支持矩阵先收窄为 `UEFI + iPXE + kickstart/cloud-init` 单一路径，不在本阶段讨论 legacy BIOS、多发行版模板分叉或更多引导模式。
- 以 [satellite/pkg/pxe/](../satellite/pkg/pxe/) 现有实现为基础，收敛运行前提、网络要求、DHCP option `66/67`、镜像准备、失败回退与运维说明。
- 定稿时 [documentation/runbooks/](runbooks/) 下**没有** PXE 专项 runbook；Round 2 已新增 [runbooks/pxe.md](runbooks/pxe.md)，用于沉淀前置条件、网络要求、镜像准备、失败回退与验收步骤。
- 本阶段强调"生产硬化准备"而非"真实环境验证已完成"；一切真实装机验证都等设备可用后再执行。
- 不新增 PXE 功能，不引入新的装机编排层，不改现有 Core / Satellite 协议契约。

### Step 3 — `load-test`：从静态门槛升级为趋势基线

**目标**：在不调整现有 runner 规模与阈值的前提下，把现有 `load-test` 从"一次通过即可"推进到"连续多次可比较"。

- 当前 CI 基线保持不变：`20 satellites / 30s / registration >= 95% / heartbeat success >= 99% / heartbeat failures <= max(5, 1%)`，口径继续以 [CI_CONTRACT.md](CI_CONTRACT.md) 与 `.github/workflows/ci.yml` 为准。
- Round 2 起将 `documentation/LOAD_TEST_BASELINES.md` 设为**单一基线记录入口**，按时间顺序追加最近 5 次绿色运行的 `LOADGEN_SUMMARY` 摘要（至少包含 run ID、commit SHA、satellites、duration、registration success rate、heartbeat success rate、heartbeat failures）。
- CI 日志与 artifact 继续作为原始证据，但**不作为**长期趋势基线的事实源；长期可比较口径统一回写到 `documentation/LOAD_TEST_BASELINES.md`。
- 在没有 GitHub runner 实测依据前，禁止放大压测规模、放宽阈值或修改 readiness 超时。
- 未来如果进入实现阶段，所有 `load-test` 相关变更必须先跑 `./scripts/check_ci_contract.sh`，并显式记录旧值、新值与调整理由。

### Step 4 — Redfish / BMC：保持 readiness，不新增功能开发

**目标**：承认真实设备不可用这一现实，把 BMC 近期目标从"继续做真实准入"调整为"保持 readiness 资产热身状态"。

- 继续以 [REDFISH_BMC_PHASE7_PLAN.md](REDFISH_BMC_PHASE7_PLAN.md)、[REDFISH_BMC_PHASE8_PLAN.md](REDFISH_BMC_PHASE8_PLAN.md) 与 [hardware-acceptance/](hardware-acceptance/) 作为事实基础，不新增独立 BMC 计划文档。
- Core 侧 mock / fixture 边界保持显式：管理面、claim、power-actions 与 session 协议路径继续由 [RedfishMockServer.java](../core/src/test/java/com/sc/lcm/core/support/RedfishMockServer.java) 承接；其 fixture 数据通过 `resolveRepoPath(...)` 直接复用 Satellite 侧的 `satellite/pkg/redfish/testdata/vendor-fixtures/*.json`。Satellite 侧采集回归继续由 `adapter_test.go`、`openbmc_adapter_test.go`、`vendor_fixture_test.go`、`session_test.go` 承接，与 Core 共享同一份 fixture 事实源。
- 近期不再扩新的 Redfish / BMC 功能范围；除非出现 mock、fixture 或文档认知缺口，否则不再为"未来可能有设备"提前叠加功能；此类缺口也应以 mock / fixture / 文档补丁形式反映，不触达运行时协议。
- `documentation/hardware-acceptance/matrix.yaml` 当前 `pending:` 机制保留，待真实设备可用后直接按 [hardware-acceptance/README.md](hardware-acceptance/README.md) 的 reporting workflow 执行。
- 本阶段对外口径固定为"readiness 资产齐备、真实机型数据待填充"，不把 mock 验证表述成真实硬件准入。

### Step 5 — 文档同步节奏：定稿后再回写现状与路线图

**目标**：确保"讨论中的方案"和"对外宣称的现状"分层管理，避免滚动现状文档先于评审定稿漂移。

- 定稿判据：本主稿经至少一次 reviewer approval，且连续一轮评审无新增 blocker 后，即视为**定稿**；由下一次 commit 一次性同步回写现状与路线图文档。
- 定稿同步（2026-04-18）一次性完成：
  - [PROJECT_STATUS.md](PROJECT_STATUS.md)：已调整"已知缺口与下阶段重点"，按 Round 2 可执行项 / 外部条件门控 / 长期收敛项三层重排，并新增对本文件的引用。
  - [DEVELOPMENT_ROADMAP.md](../DEVELOPMENT_ROADMAP.md)：已更新 `Current Focus`，新增对本文件的指向；阶段历史未改、正式 Phase 编号未造。
  - [README.md](../README.md)：按原约定不变；待进入实施期后再决定是否加入 `Documentation Map`。
- 真正开始实施后，才按现有专项计划文档习惯补"status refresh / landed"段落；讨论阶段不提前写"已落地"。

## Public Interfaces

- **本主稿为纯文档定稿，不新增或修改任何 REST / gRPC 接口。**
- `lcm.proto`、现有 `/api/bmc/devices/{id}/...` 契约、现有 PXE / Satellite / Core 运行时接口在本阶段计划中保持不变。
- 本文件是阶段实施指导主稿，但**不是**新的事实源：
  - 滚动现状仍以 [PROJECT_STATUS.md](PROJECT_STATUS.md) 为准。
  - 路线图与阶段历史仍以 [DEVELOPMENT_ROADMAP.md](../DEVELOPMENT_ROADMAP.md) 为准。
  - CI / 高风险改动规范仍以 [CI_CONTRACT.md](CI_CONTRACT.md) 与仓库级 [AGENTS.md](../AGENTS.md) 为准。

## Test Plan

- **文档一致性检查**（由作者自查 + reviewer 人工核对）
  - 本文件不得与 [CI_CONTRACT.md](CI_CONTRACT.md) 中关于 `load-test`、高风险改动、验证矩阵的口径冲突。
  - 本文件不得与 [AGENTS.md](../AGENTS.md) 中"`PROJECT_STATUS.md` 是唯一滚动现状快照"的治理规则冲突。
- **风格一致性检查**（由作者自查 + reviewer 人工核对）
  - 本文档结构必须与 [ALERTMANAGER_PHASE_PLAN.md](ALERTMANAGER_PHASE_PLAN.md)、[DEMO_SMOKE_PHASE_PLAN.md](DEMO_SMOKE_PHASE_PLAN.md) 等现有 `*_PHASE_PLAN.md` 文档同构：包含 `Summary`、`Key Changes`、`Public Interfaces`、`Test Plan`、`Assumptions`。
  - 文件命名、章节顺序、边界声明与"明确不做"语气应保持同一套风格，避免另起体例。
- **评审场景检查**（由 reviewer 在 PR / 文档评审中核对）
  - Reviewer 只看本文件，就能理解"为什么近期不以真实硬件验收为主线"。
  - Reviewer 能从本文件直接看到已回写的 [PROJECT_STATUS.md](PROJECT_STATUS.md) / [DEVELOPMENT_ROADMAP.md](../DEVELOPMENT_ROADMAP.md) 入口。
  - Reviewer 不需要在多个文件之间来回拼凑，才能理解当前计划全貌。
- **运行时验证说明**
  - 本主稿为纯文档改动，不承诺任何软件行为已经变化。
  - Round 2 起进入实现阶段后，按受影响范围执行 `./scripts/check_ci_contract.sh`、`cd core && ./gradlew check --no-daemon`、`cd satellite && go test ./... -count=1`、`cd frontend && npm test && npm run lint && npm run build`。

## Assumptions

- 当前到至少下一轮阶段评审前，都没有真实 BMC 或裸机节点可用于正式验收。
- 当前没有可用的 Slack / PagerDuty / Email 真实 secret，因此 AlertManager 只能写成"部署与路由就绪"，不能写成"真实送达已验证"。
- 近期目标是降低未来接入风险、提升文档与验证准备度，而不是扩展新的业务能力或提前进入多集群联邦开发。
- 本文件是阶段实施指导主稿；它不替代 [PROJECT_STATUS.md](PROJECT_STATUS.md) 的现状快照角色，也不替代 [DEVELOPMENT_ROADMAP.md](../DEVELOPMENT_ROADMAP.md) 的阶段历史角色。

## Round 2 实施清单（已落地产物 + 后续阻塞项）

| 项 | 状态 | 目标产物 | 所在 Step | 前置条件 |
|----|------|----------|-----------|----------|
| PXE 生产硬化 runbook | 已完成（2026-04-18） | [runbooks/pxe.md](runbooks/pxe.md)，沉淀前置条件、网络要求、镜像准备、失败回退、验收步骤 | [Step 2](#step-2--pxe--ipxe聚焦单一路径的生产硬化准备) | 无（纯文档） |
| load-test 基线入口 | 已完成（2026-04-18） | [LOAD_TEST_BASELINES.md](LOAD_TEST_BASELINES.md) 已建立，并补齐最近 5 次绿色主线基线 | [Step 3](#step-3--load-test从静态门槛升级为趋势基线) | 最近一次 CI `load-test` job 绿 |
| AlertManager 真实送达验证 | 待外部条件 | 按 [runbooks/alertmanager.md](runbooks/alertmanager.md) 完成 Slack / PagerDuty / Email 冒烟，并回写 [PROJECT_STATUS.md](PROJECT_STATUS.md) | [Step 1](#step-1--alertmanager从真实通道验证切到部署与本地路由就绪) | 真实 Slack / PagerDuty / SMTP secret 到位 |
| 真实硬件 BMC 准入扩面 | 待外部条件 | 按 [hardware-acceptance/README.md](hardware-acceptance/README.md) 的 reporting workflow 填充 `matrix.yaml` | [Step 4](#step-4--redfish--bmc保持-readiness不新增功能开发) | 实验台 OpenBMC + 商业 BMC 硬件到位 |

## 关键文件清单

- **本阶段主稿**：
  - [documentation/SOFTWARE_CLOSURE_PHASE_PLAN.md](SOFTWARE_CLOSURE_PHASE_PLAN.md) — 已定稿
- **本次定稿同步的现状文档**（Round 1 + Step 5 合并 commit）：
  - [documentation/PROJECT_STATUS.md](PROJECT_STATUS.md) — 已更新"已知缺口与下阶段重点"顺序与对本文件的引用
  - [DEVELOPMENT_ROADMAP.md](../DEVELOPMENT_ROADMAP.md) — 已更新 `Current Focus`
- **本阶段引用但不重写**：
  - [documentation/ALERTMANAGER_PHASE_PLAN.md](ALERTMANAGER_PHASE_PLAN.md)
  - [documentation/REDFISH_BMC_PHASE7_PLAN.md](REDFISH_BMC_PHASE7_PLAN.md)
  - [documentation/REDFISH_BMC_PHASE8_PLAN.md](REDFISH_BMC_PHASE8_PLAN.md)
  - [documentation/hardware-acceptance/README.md](hardware-acceptance/README.md)
  - [documentation/CI_CONTRACT.md](CI_CONTRACT.md)
- **Round 2 已新增**：
  - [documentation/runbooks/pxe.md](runbooks/pxe.md)
  - [documentation/LOAD_TEST_BASELINES.md](LOAD_TEST_BASELINES.md)
