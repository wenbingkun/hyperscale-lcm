# Agent Execution Contract

本文适用于 Codex 以及其他会读取仓库级 agent 说明的工具。规则面向**所有** agent，不只针对某一个工具。

## 0. 快速入口

在开始任何任务前，先建立项目认知：

- **项目现状与下阶段重点**：[documentation/PROJECT_STATUS.md](documentation/PROJECT_STATUS.md) — 滚动更新的能力矩阵、架构概览、已知缺口
- **路线图与阶段历史**：[DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md)
- **架构设计**：[documentation/ENTERPRISE_LCM_ARCHITECTURE.md](documentation/ENTERPRISE_LCM_ARCHITECTURE.md) · [documentation/RESOURCE_SCHEDULING_DESIGN.md](documentation/RESOURCE_SCHEDULING_DESIGN.md)

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

优先级：`CI_CONTRACT` > `AGENTS` > `PROJECT_STANDARDS`。冲突时以高优先级为准。

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

批量修改多个子系统时，三个都要跑一遍。

## 5. 变更策略

- 修 CI 优先做最小闭环修复，不顺手改无关业务
- 区分主故障和日志噪音，例如 OTel exporter `localhost:4317` 不能默认视为主失败因
- 修改 load-test 基线前，必须给出 runner 容量依据
- **默认目标分支是 `main`**，未经用户指定不要切换
- 禁止未经授权 `push --force` / `reset --hard` / `commit --amend` 已发布提交
- 提交信息遵循 Conventional Commits（`feat:` / `fix:` / `docs:` / `refactor:` / `chore:` / `ci:`）

## 6. PR 与协作

- 创建 PR 时填写 [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md) 的全部段落，尤其是 **CI Contract 自检** 与 **风险评估**
- 高风险改动需在 PR 描述里显式列出已执行的验证命令与结果
- Code owner 参见 [.github/CODEOWNERS](.github/CODEOWNERS)

## 7. 工作风格

- **审查/分析请求**默认只输出报告，不直接编辑文件；收到"修复 / 改 / 应用 / 去做"等明确动词后才动手
- 非 trivial 改动（多文件、跨子系统、架构调整）必须先出实现计划，再请求确认
- 临时/一次性分析资产放在 `.local/`（已被 .gitignore 排除）
