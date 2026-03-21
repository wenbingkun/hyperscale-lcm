# Claude Code 工作规范

## 项目结构

这是一个多语言 Monorepo，包含以下子系统：

- `core/` — Java / Quarkus 后端服务（主体业务逻辑、REST API、gRPC、Kafka）
- `satellite/` — Go 卫星代理（BMC/Redfish 数据采集）
- `frontend/` — TypeScript / React 前端（Vite 构建）
- `documentation/` — 设计文档与 Redfish 模板样例库

搜索问题时，务必明确目标子系统和文件类型，避免跨语言混淆。

## 工作风格（重要）

**审查或审计请求时，先输出分析和计划，不得直接编辑文件。**
除非用户明确说"去做"、"修复它"或"应用这些修改"，否则只输出发现内容并等待确认。

- 收到"审查"、"检查"、"分析"、"看看"等关键词 → 输出报告，停下来等待
- 收到"修复"、"改"、"应用"、"去做"等关键词 → 才可以开始编辑

## Git 工作流

- **提交前必须确认目标分支**，默认始终使用 `main`，除非用户明确指定其他分支
- 不得在未经用户授权的情况下 `push --force` 或 `reset --hard`
- 创建新提交，不得在未经明确要求时 `--amend` 已有提交
- 提交信息末尾附上：`Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>`

## 测试规范

- 修复 CI/CD 或测试失败后，**推送前在本地运行完整测试套件**，确认无连锁故障
- Java 测试：`./mvnw -pl core test -q`
- Go 测试：`go test ./... -count=1`（在 `satellite/` 目录下）
- 前端构建验证：`npm run build`（在 `frontend/` 目录下）
- 批量修改多个子系统后，三个都要验证

## 环境说明

- CI 需要 PostgreSQL/Kafka/Redis，本地单元测试通过 Testcontainers 自动启动
- `E2EIntegrationTest` 依赖本地运行的 PostgreSQL，在纯开发环境中预期失败，属正常现象
- BMC 厂商兼容性回归测试需要真实硬件或脱敏响应夹具，不在日常 CI 范围内
