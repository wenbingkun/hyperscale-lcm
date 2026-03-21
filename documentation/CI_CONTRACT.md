# CI 契约与变更约束

## 1. 目的

本文是本仓库关于 CI/CD 变更的单一事实来源。

适用对象：

- 人工开发者
- Claude Code
- Codex / 其他本地 AI agent

凡涉及 CI/CD、测试基础设施、Quarkus 运行配置、load-test、E2E 集成链路的改动，均以本文为准。

## 2. 高风险改动范围

以下任一改动都视为高风险 CI 变更：

- `.github/workflows/**`
- `core/src/main/resources/application*.properties`
- `core/src/test/**`
- `core/src/main/java/**` 中涉及 `gRPC`、`scheduler`、`Kafka`、`Redis`、数据库、claim/rotation、health、TLS 的代码
- `satellite/cmd/loadgen/**`
- `docker-compose*.yml`
- `scripts/generate_keys.sh`

触发高风险后，必须执行本文的验证矩阵，不能只做静态阅读。

同时建议先执行一次快速静态预检查：

```bash
./scripts/check_ci_contract.sh
```

该脚本不会替代运行时验证，但可以在重型测试前先拦住已知高频错误。

## 3. 强制排障流程

收到“CI/CD 报错”类任务时，必须按以下顺序处理：

1. 先定位 GitHub `run ID`
2. 再确认失败的 `job`
3. 再确认失败的 `step`
4. 提取关键报错原文
5. 再去看代码和配置

禁止以下行为：

- 未查看失败日志就直接猜原因
- 只看 commit message 不看失败 job/step
- 把日志噪音当成主故障

输出结论时，至少必须包含：

- `run ID`
- `job 名`
- `step 名`
- 关键报错
- 本次修复对应的根因分类

## 4. 强制验证矩阵

### 4.1 改动 Core 配置 / 测试 / 集成链路

必须执行：

```bash
chmod +x scripts/generate_keys.sh && ./scripts/generate_keys.sh
cd core
env QUARKUS_DATASOURCE_REACTIVE_URL=postgresql://localhost:5432/lcm \
    QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/lcm \
    QUARKUS_DATASOURCE_USERNAME=lcm \
    QUARKUS_DATASOURCE_PASSWORD=lcm_password \
    QUARKUS_REDIS_HOSTS=redis://localhost:6379 \
    KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
    ./gradlew check --no-daemon
```

如果本地没有 PostgreSQL / Redis / Kafka，则必须先说明，并显式指出未完成验证的部分。

补充说明：

- 上述命令以 GitHub Actions 当前 workflow 为准
- 如果使用仓库自带的 `docker-compose.yml` 复现，本地 compose 默认数据库名和用户名可能与 workflow 不同，必须显式对齐后再执行验证
- 不允许在“服务参数没对齐”的情况下声称本地验证已通过

### 4.2 改动 Satellite

必须执行：

```bash
cd satellite
go test ./... -count=1
```

### 4.3 改动 Frontend

必须执行：

```bash
cd frontend
npm run build
```

### 4.4 改动 load-test / prod profile / health / gRPC / TLS

除 `4.1` 外，至少还必须验证以下一项：

- `core` fast-jar 能在与 CI 对齐的环境变量下启动
- readiness path 返回 `200`
- gRPC / HTTP / health probe 路径与 `.github/workflows/ci.yml` 保持一致

如果没有做到，禁止声称“CI 应该没问题”。

## 5. 配置写法硬规则

### 5.1 可选配置

如果配置允许为空，禁止使用以下写法：

```java
@ConfigProperty(name = "...", defaultValue = "")
String value;
```

必须改为：

- `Optional<String>`
- 或明确非空默认值

原因：

- Quarkus / SmallRye Config 会把显式空值视为缺失，启动期容易直接失败

### 5.2 Scheduler cron

所有 Quarkus `@Scheduled` cron 必须使用 Quarkus 支持的 `6` 或 `7` 段格式。

禁止将 5 段 Linux cron 直接写入 Quarkus 配置。

### 5.3 环境依赖显式化

新增以下任一依赖时，必须同步检查 CI：

- PostgreSQL
- Redis
- Kafka
- gRPC
- TLS 证书 / truststore
- 外部服务地址 / secret manager

不得依赖：

- 本地默认端口
- 开发机上已运行的服务
- 本地已有证书文件
- IDE 或 shell 隐式注入的环境变量

## 6. load-test 基线规则

修改以下内容时，必须附带理由：

- 卫星数量
- 压测时长
- readiness 超时时间
- registration / heartbeat 通过阈值

必须同时说明：

- 旧值
- 新值
- 为什么改
- 是否基于 GitHub runner 实测

禁止无依据地把压测规模调大。

## 7. Agent 约束

所有 agent 必须遵守：

- CI 问题先查运行和日志，再分析代码
- 高风险改动后必须执行验证矩阵
- 未验证完成时，禁止建议用户直接提交推送
- 不得在 agent 文档中写与仓库实际不一致的工具链说明

说明：

- 本地 `CLAUDE.md`、`AGENTS.md` 或其他 agent 提示文件如果存在，应遵循本文
- 仓库级规范不依赖这些本地文件是否被提交

## 8. 推荐的 MCP / Skills

以下不是强依赖，但强烈建议配齐：

### 8.1 MCP

- GitHub Actions MCP
  - 用途：列出失败 run、查看 job、抓日志、必要时重跑
- Docker / Runtime MCP
  - 用途：检查 PostgreSQL / Redis / Kafka 容器状态、端口和 health

### 8.2 Skills

- `ci-failure-triage`
  - 固定流程：run -> job -> step -> 关键报错 -> 根因模式
- `quarkus-ci-guard`
  - 固定检查：空配置、cron、health path、gRPC、TLS、profile 差异
- `load-test-guard`
  - 固定检查：基线值、审批开关、readiness、shared/plaintext gRPC、runner 容量

说明：

- MCP 和 skill 是提效工具，不是规范本体
- 规范本体始终是本文

## 9. 与历史复盘的关系

历史故障模式见 [CI_FAILURE_PATTERNS.md](CI_FAILURE_PATTERNS.md)。

排障时先看本文，再对照复盘文档定位是否属于已知模式。

仓库内已提供对应 guard 脚本：

- `scripts/check_quarkus_ci_contract.sh`
- `scripts/check_load_test_contract.sh`
- `scripts/check_ci_contract.sh`
