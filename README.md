# 🚀 Hyperscale LCM

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen)](/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Java](https://img.shields.io/badge/java-21-orange)](https://openjdk.org/)
[![Go](https://img.shields.io/badge/go-1.21-00ADD8)](https://golang.org/)
[![React](https://img.shields.io/badge/react-18-61DAFB)](https://react.dev/)

**万级节点生命周期管理系统** - 专为大规模 GPU 集群设计的分布式作业调度平台

> **Hyperscale Lifecycle Management** — A distributed job scheduling platform designed for large-scale GPU clusters, managing 10,000+ nodes with intelligent topology-aware scheduling.

## 📋 功能特性

| 特性 | 描述 |
|-----|------|
| **响应式架构** | 基于 Quarkus + Mutiny 的全异步非阻塞设计 |
| **智能调度** | Timefold 约束求解器，支持 GPU 拓扑感知 (NVLink/IB) |
| **分区并行** | 按 Zone 分区并行调度，支持万级节点 |
| **gRPC 通信** | mTLS 双向认证，高性能二进制协议 |
| **实时监控** | WebSocket 推送，Prometheus 指标，Jaeger 分布式追踪 |
| **设备发现** | 零接触纳管：网络扫描 + Redfish BMC 自动发现 |
| **多租户** | 资源配额隔离，RBAC 权限控制 (ADMIN/OPERATOR/USER) |
| **云原生** | Kubernetes 部署 + Helm Chart，优雅启停 |
| **CI/CD** | GitHub Actions 自动化构建、测试与 Docker 镜像发布 |

## 🏗️ 系统架构

```
┌──────────────────────────────────────────────────────────────┐
│                     Frontend (React 18)                       │
│           Dashboard + Job 管理 + 设备发现 + 多租户             │
│                   (WebSocket 实时更新)                         │
└──────────────────────────┬───────────────────────────────────┘
                           │ REST API + WebSocket
┌──────────────────────────▼───────────────────────────────────┐
│                      Core Service (Quarkus)                    │
│  ┌─────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐      │
│  │ REST API│  │ gRPC Svc │  │ Scheduler │  │ Metrics  │      │
│  │ (Jobs)  │  │(Satellite)│  │(Timefold) │  │(Prom+OTel)│    │
│  └────┬────┘  └────┬─────┘  └─────┬────┘  └──────────┘      │
│       │            │              │                           │
│  ┌────▼────────────▼──────────────▼────┐                     │
│  │      Message Bus (Kafka) + DLQ       │                     │
│  └──────────────────────────────────────┘                     │
│       │                                                       │
│  ┌────▼─────────────────────┐  ┌──────────────────────┐      │
│  │ PostgreSQL (Hibernate RX)│  │ Redis (State Cache)  │      │
│  └──────────────────────────┘  └──────────────────────┘      │
└──────────────────────────────────────────────────────────────┘
         │ gRPC (mTLS)              │ Kafka
┌────────▼────────┐  ┌──────────────▼──────────────────────────┐
│   Satellite 1   │  │   Satellite 2   │  ...  │ Satellite N  │
│  (GPU Node)     │  │  (GPU Node)     │       │ (GPU Node)   │
│  - Docker Exec  │  │  - Docker Exec  │       │ - Docker Exec│
│  - Heartbeat    │  │  - Heartbeat    │       │ - Heartbeat  │
│  - Redfish BMC  │  │  - Redfish BMC  │       │ - Redfish BMC│
│  - Net Scanner  │  │  - Net Scanner  │       │ - Net Scanner│
└─────────────────┘  └─────────────────┘       └──────────────┘
```

## 🛠️ 技术栈

### Core 服务 (Java 21)
- **Quarkus 3.x** - 云原生 Java 框架
- **Hibernate Reactive** - 响应式 ORM
- **Timefold Solver** - 约束优化调度 (GPU 拓扑感知)
- **SmallRye JWT** - 认证授权 (RBAC)
- **gRPC + mTLS** - 安全通信
- **Kafka** - 异步消息 (含 Dead Letter Queue)
- **OpenTelemetry** - 分布式追踪

### Satellite 服务 (Go 1.21)
- **gRPC Client** - 与 Core 通信 (mTLS)
- **Docker SDK** - 容器执行
- **KeepAlive** - 长连接心跳
- **Redfish Client** - BMC 硬件数据采集
- **Network Scanner** - 设备主动发现

### Frontend (React 18 + TypeScript)
- **Vite** - 构建工具
- **React Router** - 路由管理
- **WebSocket** - 实时状态更新
- **Recharts** - 数据可视化

### 基础设施
- **PostgreSQL** - 主数据库
- **Redis** - 状态缓存
- **Kafka** - 消息队列
- **Jaeger** - 分布式追踪
- **Prometheus** - 指标采集

## 🚀 快速开始

### 前置要求
- Java 21+
- Go 1.21+
- Docker & Docker Compose
- Node.js 18+ (Frontend)

### 方式一：一键启动

```bash
# 启动所有服务 (基础设施 + Core + Satellite + Frontend)
./start_all.sh
```

### 方式二：分步启动

#### 1. 启动基础设施

```bash
docker-compose up -d postgres redis kafka jaeger
```

#### 2. 启动 Core 服务

```bash
cd core
./gradlew quarkusDev
```

#### 3. 启动 Satellite (可选)

```bash
cd satellite
# 配置环境变量 (参考 .env.example)
export CORE_ADDRESS=localhost:9000
export CERT_DIR=./certs
go run ./cmd/satellite
```

#### 4. 启动 Frontend

```bash
cd frontend
npm install && npm run dev
```

### 方式三：生产环境 Docker 部署 (推荐)

此项目提供了生产级的 `docker-compose.prod.yml`，它将启动所有核心服务、基础设施及前端，并直接使用已发布的 Docker 镜像。

```bash
# （可选）配置您的自定义环境变量，例如使用自定义命名空间或标签
export DOCKER_NAMESPACE=myorg      # 替换为您的 Docker Hub 组织或用户名
export DOCKER_TAG=latest           # 指定镜像版本标签
export DB_PASSWORD=your_secure_db_pwd

# 一键启动生产环境集群
docker-compose -f docker-compose.prod.yml up -d
```
> **提示**：如果您是基于当前仓库自行构建了镜像并推送到了自己的 Harbor 或 Docker Hub，只需修改 `DOCKER_NAMESPACE` 指向您的 Registry 即可无缝切换部署。

### 5. 访问服务

| 服务 | 地址 |
|-----|------|
| REST API | http://localhost:8080/api |
| Swagger UI | http://localhost:8080/q/swagger-ui |
| Metrics | http://localhost:8080/q/metrics |
| Health | http://localhost:8080/q/health |
| Frontend | http://localhost:5173 |
| Jaeger UI | http://localhost:16686 |
| Prometheus | http://localhost:9090 |

## 📡 API 示例

### 登录获取 Token

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.token')
```

### 提交作业

```bash
curl -X POST http://localhost:8080/api/jobs \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "Training Job",
    "cpuCores": 8,
    "memoryGb": 32,
    "gpuCount": 4,
    "gpuModel": "A100"
  }'
```

### 查看作业状态

```bash
curl http://localhost:8080/api/jobs \
  -H "Authorization: Bearer $TOKEN"
```

### 查看集群状态

```bash
curl http://localhost:8080/api/nodes/stats \
  -H "Authorization: Bearer $TOKEN"
```

## 📁 项目结构

```
hyperscale-lcm/
├── core/                   # Java Core 服务 (Quarkus)
│   ├── src/main/java/      # 源代码 (DDD 分层)
│   │   └── com/sc/lcm/core/
│   │       ├── api/        # REST & gRPC 接口层
│   │       ├── domain/     # 领域模型
│   │       ├── service/    # 应用服务层
│   │       └── infra/      # 基础设施层
│   ├── src/test/java/      # 单元测试
│   ├── src/main/resources/ # 配置 & Flyway 迁移
│   └── build.gradle        # Gradle 构建配置
├── satellite/              # Go Satellite 服务
│   ├── cmd/satellite/      # 主程序入口
│   ├── internal/           # 内部包
│   │   ├── agent/          # 核心 Agent 逻辑
│   │   ├── executor/       # Docker 执行器
│   │   ├── discovery/      # 网络扫描 & 设备发现
│   │   └── redfish/        # BMC Redfish 客户端
│   └── go.mod              # Go Module
├── frontend/               # React 前端 (TypeScript + Vite)
│   └── src/
│       ├── pages/          # 页面组件 (Dashboard, Jobs, Nodes, ...)
│       ├── components/     # 通用 UI 组件
│       ├── contexts/       # React Context (Auth, WebSocket)
│       └── services/       # API 客户端
├── documentation/          # 设计文档 & 分析报告
├── helm/                   # Helm Chart (Kubernetes 部署)
├── k8s/                    # Kubernetes 原始清单
├── scripts/                # 运维脚本 (loadgen 等)
├── certs/                  # TLS/mTLS 证书
├── .github/workflows/      # CI/CD (GitHub Actions)
├── docker-compose.yml      # 开发环境基础设施
├── .env.example            # 环境变量模板
└── start_all.sh            # 一键启动脚本
```

## ⚙️ 配置

主要配置通过环境变量设置，参考 `.env.example`：

| 变量 | 描述 | 默认值 |
|-----|------|--------|
| `DB_URL` | 数据库连接 | `jdbc:postgresql://localhost:5432/lcm_db` |
| `DB_USERNAME` | 数据库用户 | `lcm_user` |
| `DB_PASSWORD` | 数据库密码 | (需设置) |
| `REDIS_URL` | Redis 连接 | `redis://localhost:6379` |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka 地址 | `localhost:9092` |
| `GRPC_CERT_PATH` | gRPC 服务端证书 | `./certs/server.pem` |
| `GRPC_KEY_PATH` | gRPC 私钥 | `./certs/server-pkcs8.key` |
| `CORE_ADDRESS` | Satellite 连接 Core 地址 | `localhost:9000` |
| `CERT_DIR` | Satellite 证书目录 | `./certs` |
| `CORS_ORIGINS` | CORS 允许的前端地址 | `http://localhost:5173` |

## 🔐 安全

- **JWT 认证**: 所有 API 端点需要有效 Token
- **RBAC**: ADMIN / OPERATOR / USER 三级权限
- **mTLS**: gRPC 双向证书认证
- **资源配额**: 按租户限制资源使用

## 📊 监控

- **Prometheus**: `/q/metrics` 暴露自定义指标
- **Jaeger**: 分布式追踪可视化 (OpenTelemetry)
- **WebSocket**: 实时状态推送
- **Health Check**: `/q/health` 健康检查端点

## 🧪 测试

```bash
# 运行 Core 单元测试
cd core && ./gradlew test

# 运行 Satellite 测试
cd satellite && go test ./...

# 运行 Frontend 构建验证
cd frontend && npm run build
```

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！请遵循 [Conventional Commits](https://www.conventionalcommits.org/) 规范。

## 📄 许可证

Apache License 2.0

---

*Powered by Antigravity AI © 2026*
