# Agent Execution Contract

本文是本仓库给所有 AI 编码代理（Claude Code、Codex 等）的唯一项目说明；`CLAUDE.md` 只做引用（`@AGENTS.md`），不单独维护内容。规则面向**所有** agent，仅 §9 只适用于 Claude Code。

## 0. 快速入口

在开始任何任务前，先建立项目认知：

- **项目现状与下阶段重点**：[documentation/PROJECT_STATUS.md](documentation/PROJECT_STATUS.md) — 滚动更新的能力矩阵、架构概览、已知缺口
- **路线图与阶段历史**：[DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md)
- **架构设计**：[documentation/ENTERPRISE_LCM_ARCHITECTURE.md](documentation/ENTERPRISE_LCM_ARCHITECTURE.md) · [documentation/RESOURCE_SCHEDULING_DESIGN.md](documentation/RESOURCE_SCHEDULING_DESIGN.md)

### 仓库结构

多语言 Monorepo，一级目录：

- `core/` — Java 21 / Quarkus 后端（业务逻辑、REST API、gRPC、Kafka、Timefold 调度）
- `satellite/` — Go 卫星代理（BMC/Redfish 采集、PXE、Docker/Shell/Ansible/SSH 执行）
- `frontend/` — TypeScript / React 前端（Vite 构建，Vitest 测试）
- `documentation/` — 设计文档、CI 契约、Redfish 模板样例、硬件验收矩阵
- `scripts/` — 构建、验证、演示与 CI guard 脚本（`check_ci_contract.sh`、`generate_keys.sh`、`demo.sh`）
- `helm/`、`k8s/`、`infra/` — 部署资产
- `certs/` — 本地 TLS/mTLS 证书（被 .gitignore 排除，通过脚本生成）
- `.github/` — CI 工作流、PR 模板、CODEOWNERS
- `.local/` — 本地专属目录（被 .gitignore 排除），放个人 TODO / 草稿 / agent 实验记录，见 [.local/README.md](.local/README.md)

搜索问题时先明确目标子系统和文件类型，避免跨语言混淆。

## 1. 单一事实来源

涉及 CI/CD、测试环境、Quarkus 配置、load-test、gRPC / TLS / health probe 的任务时，必须优先遵循：

- [documentation/CI_CONTRACT.md](documentation/CI_CONTRACT.md)
- [documentation/CI_FAILURE_PATTERNS.md](documentation/CI_FAILURE_PATTERNS.md)

不得在本文件中重复维护另一套测试命令或 CI 规则。

### 规范文件分工

| 文件 | 性质 | 应用场景 |
|------|------|---------|
| `CI_CONTRACT.md` / `CI_FAILURE_PATTERNS.md` | 事实源 | CI/测试/高风险改动 — **最高优先级** |
| `AGENTS.md`（本文件） | 硬契约 | 任何 agent 必须遵守的流程规则 |
| `PROJECT_STANDARDS.md` | 工程规范 | 技术栈、代码风格、DDD 架构、API 约定 |
| `PROJECT_STATUS.md` | 现状快照 | 建立项目认知的入口 |

优先级：`CI_CONTRACT` > `AGENTS` > `PROJECT_STANDARDS`。冲突时以高优先级为准。`CLAUDE.md` 仅引用本文件，不参与优先级。

## 2. CI 故障处理硬规则

收到 CI/CD 报错任务时，必须先确认：

1. `run ID`
2. 失败 `job`
3. 失败 `step`
4. 关键报错原文

在拿到这四项之前，禁止直接猜原因。禁止只看 commit message 或工作流名称就做判断。

## 3. 高风险改动的最小要求

当改动以下内容时，必须执行 `CI_CONTRACT.md` 规定的验证矩阵：

- `.github/workflows/**`
- `application*.properties`
- `core/src/test/**`
- `load-test`
- `gRPC / Kafka / Redis / DB / scheduler / health / TLS` 相关代码

未完成验证时，必须明确说明，禁止声称"应该已经修好"。

高风险改动在跑重型测试前，先执行：

```bash
./scripts/check_ci_contract.sh
```

该脚本只是快速 guard，不替代 `CI_CONTRACT.md` 中的运行时验证矩阵。

## 4. 工具链事实

- `core` 使用 Gradle（**不**使用 Maven），Java 21 + Quarkus 3.6.4 + Timefold 1.4.0
- `satellite` 使用 Go 1.24 + Go Modules
- `frontend` 使用 `npm`（**不**使用 yarn/pnpm），React 19 + Vite + Vitest
- 本地依赖服务：PostgreSQL 15 + Redis + Kafka（由 `docker-compose.yml` 拉起）

不要在分析、计划或修复建议中引用与仓库实际不一致的命令或版本。

### 本地验证矩阵

| 子系统 | 命令 | 工作目录 |
|--------|------|---------|
| Core | `./gradlew check --no-daemon` | `core/` |
| Satellite | `go test ./... -count=1` | `satellite/` |
| Frontend | `npm test && npm run lint && npm run build` | `frontend/` |
| CI Contract guard | `./scripts/check_ci_contract.sh` | 仓库根 |

批量修改多个子系统时，三个都要跑一遍。修复 CI/测试失败后，推送前在本地跑完整套件，确认无连锁故障。

### 环境说明

- CI 需要 PostgreSQL / Kafka / Redis；本地复现 Backend Tests 时尽量与 CI 保持一致
- `E2EIntegrationTest` 不应默认视为"本地必然失败"；复现 CI 问题时先准备数据库、Redis、Kafka 及对应环境变量
- BMC 厂商兼容性：OpenBMC、iDRAC、iLO、XCC 的脱敏响应夹具回归（`satellite/pkg/redfish/vendor_fixture_test.go`）随 Satellite 的 `go test ./...` 纳入日常 CI；真实硬件验收不在日常 CI 范围内

## 5. 变更策略

- 修 CI 优先做最小闭环修复，不顺手改无关业务
- 区分主故障和日志噪音，例如 OTel exporter `localhost:4317` 不能默认视为主失败因
- 修改 load-test 基线前，必须给出 runner 容量依据
- **默认目标分支是 `main`**，提交前确认目标分支，未经用户指定不要切换
- 禁止未经授权 `push --force` / `reset --hard` / `commit --amend` 已发布提交
- 提交信息遵循 Conventional Commits（`feat:` / `fix:` / `docs:` / `refactor:` / `chore:` / `ci:`），末尾附 `Co-Authored-By: Claude <noreply@anthropic.com>`（不写具体型号）

## 6. PR 与协作

- 创建 PR 时填写 [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md) 的全部段落，尤其是 **CI Contract 自检** 与 **风险评估**
- 高风险改动需在 PR 描述里显式列出已执行的验证命令与结果
- Code owner 参见 [.github/CODEOWNERS](.github/CODEOWNERS)

## 7. 工作风格

- **审查/分析请求**默认只输出报告，不直接编辑文件；收到"修复 / 改 / 应用 / 去做"等明确动词后才动手
- 非 trivial 改动（多文件、跨子系统、架构调整）必须先出实现计划，再请求确认
- 临时/一次性分析资产放在 `.local/`（已被 .gitignore 排除）

## 8. 多代理分工

- Claude Code 负责开发：探索、实现、跑测试。主会话用 sonnet，检索交给 haiku 的 Explore 子代理；opus advisor 只在 Claude 自行判断的决策点给按需建议（子代理也会继承），不算审核关卡。
- Codex 负责提交前审核，这是唯一的审核关卡。审核期间只读，只出意见，不改代码；需要的修改交回 Claude 完成。常规审核用 `codex --profile review`；涉及凭据、证书、部署和回滚、权限、CI 安全的改动用 `codex --profile review-deep`。两个 profile 都是只读沙箱，升权由用户确认（前提是 `~/.codex/rules/` 中没有 allow 规则，所以审批时不要选“永久允许”）：需要联网的检查经批准后运行，需要写入的验证放隔离副本。
- 上述 profile 是个人配置，是各自机器上的独立文件 `~/.codex/review.config.toml` 与 `~/.codex/review-deep.config.toml`（叠加在 `~/.codex/config.toml` 之上），不在本仓库中（也不要把凭据写进仓库）。审核前先确认两个 profile 都已定义且为只读沙箱（如 `codex --profile review --help` 无报错，并核对两个文件中的 `sandbox_mode = "read-only"`）；缺失时先告知用户补配置，不要退回到默认的可写 profile 审核。
- 同一仓库同一时间只让一个代理写代码；需要并行时用 git worktree。
- agy 不参与本仓库的开发和审核。
- 本仓库的说明文件不写具体模型版本号。

## 9. Claude Code 专用

本节只适用于 Claude Code，其他 agent 可忽略。

- **Plan mode**：涉及多文件、跨子系统、架构调整或文档大规模整理的任务，先进入 Plan mode 出计划（目标与背景、任务清单、关键文件、验证方式、不做的事），通过计划审批流程请求确认；不要用提问工具问"计划是否可以"。提问工具只用于在分岔点选择方向。trivial 改动（单行修复、重命名、typo）可直接动手。
- **子代理**：多点探索、跨子系统检索时用 Explore，可并行至多 3 个，各负责独立的搜索焦点；需要第二意见时用 Plan，默认 1 个。单文件已知路径的修改不起子代理。
- **Skill**：`/review` 走 [.claude/commands/review.md](.claude/commands/review.md) 的标准审查流程；`/simplify` 检查最近变更的冗余。
- **待办清单**：≥3 步的任务开始时建立待办清单，完成一项立即标记，不批量更新；单步任务不用。
