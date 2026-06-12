# Hyperscale LCM Helm Chart

GPU 集群作业调度与容器编排平台的 Kubernetes 部署包。

## 快速开始

```bash
# 添加依赖仓库
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update

# 安装
helm install lcm ./helm/hyperscale-lcm \
  --namespace lcm --create-namespace \
  --set global.imageRegistry=docker.io/<dockerhub-user>

# 自定义配置安装
helm install lcm ./helm/hyperscale-lcm \
  --namespace lcm --create-namespace \
  -f my-values.yaml
```

## 配置项

### Core 服务

| 参数 | 描述 | 默认值 |
|------|------|--------|
| `core.enabled` | 启用 Core 服务 | `true` |
| `core.replicaCount` | 副本数 | `2` |
| `core.image.repository` | 镜像地址 | `lcm-core` |
| `core.image.tag` | 镜像版本 | `v0.1.0` |
| `core.resources.limits.memory` | 内存限制 | `4Gi` |
| `core.autoscaling.enabled` | 启用 HPA | `true` |

### Frontend

| 参数 | 描述 | 默认值 |
|------|------|--------|
| `frontend.enabled` | 启用前端 | `true` |
| `frontend.replicaCount` | 副本数 | `2` |
| `frontend.image.repository` | 镜像地址 | `lcm-frontend` |
| `frontend.image.tag` | 镜像版本 | `v0.1.0` |

### Satellite

| 参数 | 描述 | 默认值 |
|------|------|--------|
| `satellite.enabled` | 启用 Satellite | `true` |
| `satellite.image.repository` | 镜像地址 | `lcm-satellite` |
| `satellite.image.tag` | 镜像版本 | `v0.1.0` |
| `satellite.core.grpcPort` | Core gRPC 目标端口 | `8080` |
| `satellite.tolerations` | GPU 节点容忍度 | `nvidia.com/gpu` |

### Ingress

| 参数 | 描述 | 默认值 |
|------|------|--------|
| `ingress.enabled` | 启用 Ingress | `true` |
| `ingress.hosts[0].host` | 域名 | `lcm.example.com` |
| `ingress.tls` | TLS 配置 | `[]` |

### 基础设施

| 参数 | 描述 | 默认值 |
|------|------|--------|
| `postgresql.enabled` | 内置 PostgreSQL | `true` |
| `redis.enabled` | 内置 Redis | `true` |
| `redis.auth.enabled` | 内置 Redis 认证 | `false` |
| `kafka.enabled` | 内置 Kafka | `true` |

### mTLS

| 参数 | 描述 | 默认值 |
|------|------|--------|
| `security.mtls.enabled` | 启用 Core/Satellite mTLS Secret 挂载 | `true` |
| `security.mtls.secretName` | mTLS Secret 名称 | `lcm-tls` |
| `security.mtls.mountPath` | 证书挂载目录 | `/app/certs` |
| `security.mtls.createSecret` | 由 chart 创建 mTLS Secret | `false` |

### 高可用

| 参数 | 描述 | 默认值 |
|------|------|--------|
| `podDisruptionBudget.enabled` | 启用 PDB 模板 | `true` |
| `podDisruptionBudget.core.minAvailable` | Core 最少可用副本 | `1` |
| `podDisruptionBudget.satellite.maxUnavailable` | Satellite 最大不可用比例 | `"25%"` |

### 身份与权限

| 参数 | 描述 | 默认值 |
|------|------|--------|
| `serviceAccount.create` | 为 Core / Satellite 创建独立 ServiceAccount | `true` |
| `serviceAccount.automountServiceAccountToken` | 是否自动挂载 ServiceAccount Token | `false` |
| `rbac.create` | 创建最小权限 Role / RoleBinding | `true` |

### 告警

| 参数 | 描述 | 默认值 |
|------|------|--------|
| `monitoring.alertmanager.enabled` | 启用 AlertManager Deployment / Service / ConfigMap | `true` |
| `monitoring.alertmanager.image.tag` | AlertManager 镜像版本 | `v0.28.1` |
| `monitoring.alertmanager.service.port` | AlertManager 服务端口 | `9093` |

### 网络策略

| 参数 | 描述 | 默认值 |
|------|------|--------|
| `networkPolicy.enabled` | 启用 Core / Satellite 网络策略 | `true` |
| `networkPolicy.core.ingressController.enabled` | 允许 Ingress Controller 访问 Core HTTP 端口 | `true` |
| `networkPolicy.core.prometheus.enabled` | 允许 Prometheus 抓取 Core metrics | `true` |

说明：`satellite` 当前使用 `hostNetwork: true`，不同 CNI 对 hostNetwork Pod 的 NetworkPolicy 支持存在差异；模板已限制其期望的 egress 方向，但实际效果需结合集群插件验证。

## 示例

### 生产环境配置

```yaml
# production-values.yaml
core:
  image:
    tag: v0.1.0
  replicaCount: 3
  resources:
    limits:
      cpu: 4000m
      memory: 8Gi

ingress:
  hosts:
    - host: lcm.mycompany.com
      paths:
        - path: /
          service: frontend
        - path: /api
          service: core
  tls:
    - secretName: lcm-tls
      hosts:
        - lcm.mycompany.com

postgresql:
  auth:
    password: change-me
  primary:
    persistence:
      size: 100Gi

security:
  mtls:
    enabled: true
    secretName: lcm-tls

kafka:
  enabled: true
```

### 使用外部数据库

```yaml
postgresql:
  enabled: false

core:
  env:
    QUARKUS_DATASOURCE_JDBC_URL: jdbc:postgresql://external-db:5432/lcm
    QUARKUS_DATASOURCE_USERNAME: lcm_user
```

## 升级

```bash
helm upgrade lcm ./helm/hyperscale-lcm \
  --namespace lcm -f my-values.yaml
```

## 卸载

```bash
helm uninstall lcm --namespace lcm
```
