# Hyperscale LCM 项目规范 (Project Standards)

为了保证项目的企业级质量和可维护性，所有开发者需遵循以下规范。

## 1. 技术栈规范 (Tech Stack)
*   **Core**: Java 17+, Quarkus (Latest Stable).
    *   禁止引入 Spring 依赖，保持 Native Image 兼容性。
    *   使用 Lombok 简化样板代码。
*   **Satellite**: Go 1.21+.
    *   使用 Go Modules 管理依赖。
    *   禁止使用 CGO (除非万不得已)，确保跨平台编译能力 (Linux/Windows/ARM)。
*   **Communication**: gRPC (Protobuf v3)。
*   **Database**: PostgreSQL 14+ (JSONB for dynamic properties).

## 2. 代码风格 (Code Style)
*   **Java**: 遵循 Google Java Style Guide。
    *   类名：`UpperCamelCase`
    *   方法/变量：`lowerCamelCase`
    *   常量：`UPPER_SNAKE_CASE`
*   **Go**: 遵循 `gofmt` 和 `go vet` 标准。
*   **Comments**: 关键业务逻辑必须包含中文注释。

## 3. 架构规范 (Architecture)
遵循 **DDD (领域驱动设计)** 原则：
```
com.sc.lcm.core
├── domain      // 核心业务对象 (Server, Gpu, Job) - 不依赖任何框架
├── infra       // 基础设施实现 (PostgresRepo, RedfishClient)
├── api         // 接口层 (GrpcResource, RestResource)
└── service     // 应用服务层 (编排业务流程)
```
*   **Core 不应直接依赖 Infrastructure**，使用接口倒置。

## 4. API 规范 (Interface)
*   **REST API**: 使用 OpenAPI (Swagger) 描述。
    *   路径: `/api/v1/resources` (复数名词)
    *   状态码: 200 (OK), 201 (Created), 400 (Bad Request), 500 (Internal Error).
*   **gRPC**:
    *   Service 定义在 `proto/lcm.proto`。
    *   所有 RPC 方法名使用 `VerbNoun` 格式 (e.g., `RegisterSatellite`, `ReportMetrics`).

## 5. Git 工作流 (Git Workflow)
遵循 **Conventional Commits**：
*   `feat: 增加GPU拓扑感知功能`
*   `fix: 修复Redfish解析空指针`
*   `docs: 更新README文档`
*   `refactor: 重构调度算法`

分支策略：
*   `main`: 随时可部署的稳定分支。
*   `dev`: 开发主分支。
*   `feature/xxx`: 特性开发分支。
