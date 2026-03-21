# CI/CD 失败模式复盘

## 1. 范围

本文复盘 `2026-03-09` 到 `2026-03-20` 期间 `main` 分支 `CI/CD Pipeline` 的重复失败模式，目标不是逐条记流水账，而是识别反复出现的工程性问题，并为后续约束提供依据。

观察结论：

- 失败高度集中在 `Backend Tests` 和 `Core Load Test`
- `Frontend Build`、`Satellite Build`、`CodeQL` 基本稳定
- 根因主要不是业务算法错误，而是 CI 环境契约、测试契约、运行配置和负载基线不一致

## 2. 代表性失败运行

| 日期 | Run ID | 失败 Job | 关键报错 / 现象 | 根因分类 | 后续收敛方式 |
| --- | --- | --- | --- | --- | --- |
| 2026-03-09 | `22853233140` | `Backend Tests` | Kafka `localhost:9092` 无法建立连接 | CI 依赖服务缺失 | 在 backend-test 中补齐 Kafka 服务 |
| 2026-03-11 | `22944953410` | `Backend Tests` | `E2EIntegrationTest` gRPC `ConnectException` | 测试连接目标 / 生命周期不稳定 | 显式约束 gRPC host/port 与测试启动顺序 |
| 2026-03-11 | `22946854460` | `Core Load Test` | `Cannot set quarkus.http.redirect-insecure-requests without enabling SSL` | prod 配置与 load-test 启动参数冲突 | 对齐 HTTP/HTTPS、health probe、gRPC/TLS 契约 |
| 2026-03-11 | `22961127849` | `Core Load Test` | `Validate Load Test Baseline` 失败 | 压测链路未真实稳定 | 修正 loadgen / gRPC / readiness 契约 |
| 2026-03-12 | `22987235406` | `Core Load Test` | `0/500 satellites registered`、`0 heartbeat attempts` | 压测基线与 runner 容量不匹配，叠加审批门控 | 关闭 discovery approval gate，调小基线 |
| 2026-03-18 | `23253993965` | `Core Load Test` | 负载基线再次失败 | GitHub runner 资源上限被高估 | 将压测规模从 `500` 收敛到 `200` |
| 2026-03-20 | `23344558504` | `Backend Tests` | Quarkus 启动阶段配置异常 | 可选配置与调度配置写法错误 | 可选配置改 `Optional`，cron 改为 Quarkus 合法格式 |

## 3. 重复出现的失败模式

### 3.1 CI 依赖服务与测试假设不一致

代表运行：`22853233140`

典型症状：

- Kafka `localhost:9092` 无法连接
- producer / consumer 在测试启动后持续重试
- 最终 `E2EIntegrationTest` 失败

根因：

- 后端测试代码假设 Kafka 已存在
- GitHub Actions 的 `backend-test` job 初期没有给出完整依赖服务

经验教训：

- 后端测试如果依赖 PostgreSQL、Redis、Kafka，就必须由 CI workflow 明确提供
- 不能依赖“开发机上正好有服务在跑”

### 3.2 gRPC / HTTP 目标地址和测试生命周期不稳定

代表运行：`22944953410`

典型症状：

- `E2EIntegrationTest > testEndToEndJobLifecycle() FAILED`
- `io.grpc.StatusRuntimeException`
- 底层是 `java.net.ConnectException`

根因：

- 测试使用的 gRPC host/port、shared server 行为、服务启动顺序没有在 CI 中被显式固定
- 本地默认值和 CI 实际运行方式不一致

经验教训：

- 所有 E2E / 集成测试必须显式声明连接目标
- 不允许依赖隐式默认端口或本地残留进程

### 3.3 load-test 与 prod 配置契约不一致

代表运行：`22946854460`

典型症状：

- `Core Load Test` 在 `Start Core Server Background` 阶段失败
- `Failed to start application (with profile [prod])`
- `Cannot set quarkus.http.redirect-insecure-requests without enabling SSL`

根因：

- load-test 在 `prod` 配置下启动 core，但 HTTP redirect、SSL、health path、gRPC/TLS 参数没有成套对齐

经验教训：

- `prod` 配置不能直接拿来跑 CI 负载测试，除非同时验证 readiness path、gRPC 端口、证书格式、明文/加密模式
- health probe 是契约，不是随手写的字符串

### 3.4 压测基线脱离 GitHub runner 现实能力

代表运行：`22961127849`、`22987235406`、`23253993965`

典型症状：

- `Validate Load Test Baseline` 失败
- `registration baseline failed`
- `heartbeat baseline failed`
- `0/500 satellites registered`

根因：

- 基线参数用的是理想环境假设，不是 GitHub runner 的现实容量
- 同时还有 discovery approval、gRPC/TLS、health path 等细节会进一步放大失败

经验教训：

- 压测基线必须绑定具体 runner 能力
- 不能在没有测量证据的情况下随意把 `500`、`1000` 这类值写进 CI

### 3.5 新增配置项导致 Quarkus 启动期失败

代表运行：`23344558504`

典型症状：

- `ConfigurationException`
- Quarkus 在测试启动前失败
- 随后暴露出 scheduler cron 解析失败

根因：

- 可选配置用了 `@ConfigProperty(... defaultValue = "") String`
- 新增调度器 cron 使用了 5 段格式，而 Quarkus scheduler 需要 6/7 段格式

经验教训：

- “允许空值”的配置不能按必填字符串注入
- Quarkus 调度配置必须按框架契约编写，不能沿用其他 cron 方言

## 4. 非主因但高噪音的日志

以下日志多次出现，但通常不是主失败因：

- `Failed to export spans. Connection refused: localhost:4317`
- GitHub Actions `Node.js 20 actions are deprecated`

处理原则：

- 先判断是否阻断主流程，再决定是否纳入当前修复
- 不要把 OTel exporter 噪音误判成主故障
- 但应在后续治理中尽量消除，降低排障噪音

## 5. 总结

这批 CI 故障可以归纳成一句话：

> 工程契约没有被显式固化，导致 CI 环境、测试环境、prod 配置和负载场景反复失配。

因此，后续治理重点不应只是“修一次错一次”，而应转向：

- 用单一事实来源定义 CI 契约
- 对 agent 增加硬约束，而不是软提醒
- 用固定的排障流程和验证矩阵替代经验判断

对应约束见 [CI_CONTRACT.md](CI_CONTRACT.md)。
