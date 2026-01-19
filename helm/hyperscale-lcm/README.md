# Hyperscale LCM Helm Chart

GPU 集群作业调度与容器编排平台的 Kubernetes 部署包。

## 快速开始

```bash
# 添加依赖仓库
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update

# 安装
helm install lcm ./helm/hyperscale-lcm \
  --namespace lcm --create-namespace

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
| `core.image.repository` | 镜像地址 | `hyperscale-lcm/core` |
| `core.resources.limits.memory` | 内存限制 | `4Gi` |
| `core.autoscaling.enabled` | 启用 HPA | `true` |

### Frontend

| 参数 | 描述 | 默认值 |
|------|------|--------|
| `frontend.enabled` | 启用前端 | `true` |
| `frontend.replicaCount` | 副本数 | `2` |
| `frontend.image.repository` | 镜像地址 | `hyperscale-lcm/frontend` |

### Satellite

| 参数 | 描述 | 默认值 |
|------|------|--------|
| `satellite.enabled` | 启用 Satellite | `true` |
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
| `kafka.enabled` | 内置 Kafka | `false` |

## 示例

### 生产环境配置

```yaml
# production-values.yaml
core:
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
  primary:
    persistence:
      size: 100Gi
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
