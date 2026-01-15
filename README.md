# 🚀 Hyperscale LCM

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen)](/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Java](https://img.shields.io/badge/java-21-orange)](https://openjdk.org/)
[![Go](https://img.shields.io/badge/go-1.21-00ADD8)](https://golang.org/)

**万级节点生命周期管理系统** - 专为大规模 GPU 集群设计的分布式作业调度平台

## 📋 功能特性

| 特性 | 描述 |
|-----|------|
| **响应式架构** | 基于 Quarkus + Mutiny 的全异步非阻塞设计 |
| **智能调度** | Timefold 约束求解器，支持 GPU 拓扑感知 |
| **分区并行** | 按 Zone 分区并行调度，支持万级节点 |
| **gRPC 通信** | mTLS 双向认证，高性能二进制协议 |
| **实时监控** | WebSocket 推送，Prometheus 指标 |
| **多租户** | 资源配额隔离，RBAC 权限控制 |
| **云原生** | Kubernetes 部署，优雅启停 |

## 🏗️ 系统架构

```
┌──────────────────────────────────────────────────────────────┐
│                     Frontend Dashboard                        │
│                    (React + WebSocket)                        │
└──────────────────────────┬───────────────────────────────────┘
                           │ WebSocket
┌──────────────────────────▼───────────────────────────────────┐
│                      Core Service                             │
│  ┌─────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐      │
│  │ REST API│  │ gRPC Svc │  │ Scheduler │  │ Metrics  │      │
│  │ (Jobs)  │  │(Satellite)│  │(Timefold) │  │(Prometheus)    │
│  └────┬────┘  └────┬─────┘  └─────┬────┘  └──────────┘      │
│       │            │              │                           │
│  ┌────▼────────────▼──────────────▼────┐                     │
│  │           Message Bus (Kafka)        │                     │
│  └──────────────────────────────────────┘                     │
└──────────────────────────────────────────────────────────────┘
         │ gRPC (mTLS)              │ Kafka
┌────────▼────────┐  ┌──────────────▼──────────────────────────┐
│   Satellite 1   │  │   Satellite 2   │  ...  │ Satellite N  │
│  (GPU Node)     │  │  (GPU Node)     │       │ (GPU Node)   │
│  - Docker Exec  │  │  - Docker Exec  │       │ - Docker Exec│
│  - Heartbeat    │  │  - Heartbeat    │       │ - Heartbeat  │
└─────────────────┘  └─────────────────┘       └──────────────┘
```

## 🛠️ 技术栈

### Core 服务 (Java)
- **Quarkus 3.x** - 云原生 Java 框架
- **Hibernate Reactive** - 响应式 ORM
- **Timefold Solver** - 约束优化调度
- **SmallRye JWT** - 认证授权
- **gRPC + mTLS** - 安全通信

### Satellite 服务 (Go)
- **gRPC Client** - 与 Core 通信
- **Docker SDK** - 容器执行
- **KeepAlive** - 长连接心跳

### 基础设施
- **PostgreSQL** - 主数据库
- **Redis** - 状态缓存
- **Kafka** - 消息队列
- **Jaeger** - 分布式追踪

## 🚀 快速开始

### 前置要求
- Java 21+
- Go 1.21+
- Docker & Docker Compose
- Node.js 18+ (Dashboard)

### 1. 启动基础设施

```bash
docker-compose up -d postgres redis kafka jaeger
```

### 2. 启动 Core 服务

```bash
cd core
./gradlew quarkusDev
```

### 3. 启动 Satellite (可选)

```bash
cd satellite
go run ./cmd/satellite
```

### 4. 启动 Dashboard (可选)

```bash
cd dashboard
npm install && npm run dev
```

### 5. 访问服务

| 服务 | 地址 |
|-----|------|
| REST API | http://localhost:8080/api |
| Swagger UI | http://localhost:8080/q/swagger-ui |
| Metrics | http://localhost:8080/q/metrics |
| Health | http://localhost:8080/q/health |
| Dashboard | http://localhost:5173 |
| Jaeger | http://localhost:16686 |

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
├── core/                   # Java Core 服务
│   ├── src/main/java/      # 源代码
│   ├── src/main/resources/ # 配置文件
│   └── build.gradle        # 构建配置
├── satellite/              # Go Satellite 服务
│   ├── cmd/satellite/      # 主程序
│   └── internal/           # 内部包
├── dashboard/              # React 前端
│   └── src/                # 源代码
├── k8s/                    # Kubernetes 清单
├── docker-compose.yml      # 开发环境
└── README.md               # 项目文档
```

## ⚙️ 配置

主要配置通过环境变量设置，参考 `.env.example`：

| 变量 | 描述 | 默认值 |
|-----|------|--------|
| `DB_REACTIVE_URL` | 数据库连接 | `postgresql://...` |
| `REDIS_URL` | Redis 连接 | `redis://localhost:6379` |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka 地址 | `localhost:9092` |
| `JWT_ISSUER` | JWT 签发者 | `https://lcm.example.com` |

## 🔐 安全

- **JWT 认证**: 所有 API 端点需要有效 Token
- **RBAC**: ADMIN / OPERATOR / USER 三级权限
- **mTLS**: gRPC 双向证书认证
- **资源配额**: 按租户限制资源使用

## 📊 监控

- **Prometheus**: `/q/metrics` 暴露自定义指标
- **Jaeger**: 分布式追踪可视化
- **WebSocket**: 实时状态推送

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📄 许可证

Apache License 2.0

---

*Powered by Antigravity AI © 2026*
