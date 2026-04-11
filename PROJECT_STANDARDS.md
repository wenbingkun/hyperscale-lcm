# Hyperscale LCM 项目规范 (Project Standards)

为了保证项目的可维护性与协作一致性，日常开发遵循以下长期约定。

> CI / 测试运行事实源不在本文维护，统一以 `documentation/CI_CONTRACT.md` 为准。

## 1. 技术栈规范 (Tech Stack)

- **Core**: Java 21 + Gradle + Quarkus 3.x + Timefold 1.x
  - 禁止引入 Spring 依赖。
  - Flyway 负责数据库迁移。
- **Satellite**: Go 1.24+ + Go Modules
  - 默认保持 `CGO_ENABLED=0` 友好，除非确有平台依赖理由。
- **Frontend**: Node.js 20+ + React 19 + TypeScript + Vite
- **Persistence / Messaging**: PostgreSQL 15+、Redis、Kafka
- **Communication**: REST、WebSocket、gRPC + mTLS

## 2. 代码与文档风格 (Code And Docs)

- **Java**: 保持一致的 `UpperCamelCase / lowerCamelCase / UPPER_SNAKE_CASE` 命名。
- **Go**: 遵循 `gofmt`、`go vet` 与标准库优先原则。
- **TypeScript / React**: 跟随现有 ESLint、组件拆分和类型定义模式。
- **Comments**: 只为不直观的业务逻辑补充注释，避免无信息量注释。
- **Docs**:
  - `README.md` 是总入口与文档导航。
  - `documentation/PROJECT_STATUS.md` 是滚动现状快照。
  - `DEVELOPMENT_ROADMAP.md` 记录阶段历史和路线图。
  - `documentation/CI_CONTRACT.md` / `documentation/CI_FAILURE_PATTERNS.md` 是 CI/CD 单一事实来源。

## 3. 架构约定 (Architecture)

当前仓库按“接口层 / 应用服务层 / 领域模型 / 基础设施或支持代码”组织，不再维护脱离实现现实的理想化分层描述。

- `api/` 负责 REST、gRPC、认证、请求/响应映射。
- `service/` 负责业务编排、调度、claim、执行链路。
- `domain/` 负责实体、枚举和值对象。
- `infra/`、`support/`、`pkg/` 等目录承接外部集成、适配器和测试夹具。
- 新能力优先接入已有主链路，避免并行造第二套状态机或重复入口。

## 4. 接口规范 (Interface)

- **REST API**:
  - 优先延续现有接口风格，不为了一致性强行重写成熟路径。
  - 使用清晰的状态码和可读错误信息。
- **gRPC**:
  - 协议定义统一维护在 `proto/lcm.proto`。
  - 修改 gRPC 契约时必须考虑向后兼容和联动测试。
- **Config / Secret**:
  - 凭据、地址、TLS 和第三方端点必须参数化，避免硬编码。

## 5. Git 与交付 (Git Workflow)

- `main` 是当前权威主分支。
- 如无明确流程要求，优先做小步、可验证、可直接合并的变更。
- 提交信息优先遵循 Conventional Commits：
  - `feat: 增加 GPU 拓扑感知功能`
  - `fix: 修复 Redfish 解析空指针`
  - `docs: 更新 README 文档`
  - `refactor: 重构调度算法`
- 涉及高风险配置、CI、数据库迁移、协议或测试基线的改动，必须同时更新对应验证与文档。
