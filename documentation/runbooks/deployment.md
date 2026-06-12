# Deployment 运维手册 (Runbook)

> **Last Updated:** 2026-06-12
> **Scope:** 从固定版本镜像部署 Hyperscale LCM 的 Core、Satellite、Frontend 与基础依赖；覆盖 docker-compose 单机路径、Helm 路径、mTLS Secret、必填环境变量、健康检查与验收记录。

---

## 1. 部署边界

本 runbook 面向无真实 BMC / 裸机 / 外部 AlertManager secret 的环境。可验证目标是：

- Core 以 `prod` profile 启动，gRPC mTLS `client-auth=required`。
- Satellite 使用 mTLS 注册到 Core。
- Frontend 可登录并访问 Core API。
- Kafka / Redis / PostgreSQL / AlertManager 路由可见。
- 重启后 PostgreSQL 持久化数据仍可恢复。

Core 当前配置为 gRPC 与 HTTP 共用 Quarkus 端口 `8080`，因此 Satellite 的 `LCM_CORE_ADDR` 必须指向 `core-host:8080`，不要使用历史残留的 `:9000`。

---

## 2. mTLS 证书材料

### 2.1 文件清单

Core 与 Satellite 共用同一个证书目录或 Kubernetes Secret，必须包含：

| 文件 / key | 用途 |
|------------|------|
| `ca.pem` | Satellite 校验 Core server cert 的 CA |
| `server.pem` | Core gRPC server 证书 |
| `server-pkcs8.key` | Core gRPC server 私钥，PKCS#8 |
| `client.pem` | Satellite mTLS client 证书 |
| `client.key` | Satellite mTLS client 私钥 |
| `truststore.jks` | Core 校验 client cert 的 truststore |
| `truststore-password` | `truststore.jks` 密码，默认测试值为 `changeit` |

### 2.2 生成测试证书

仓库脚本只适合测试、demo 或封闭验收环境：

```bash
./scripts/generate_keys.sh
```

生产环境应由内部 CA 或证书平台签发同名文件，并保持文件名不变。

---

## 3. docker-compose 单机路径

### 3.1 前置条件

- Linux 主机已安装 Docker 与 Docker Compose。
- 已准备并可拉取三个固定版本镜像：
  - `<dockerhub-user>/lcm-core:v0.1.0`
  - `<dockerhub-user>/lcm-satellite:v0.1.0`
  - `<dockerhub-user>/lcm-frontend:v0.1.0`
- 当前目录是仓库根目录，且 `certs/` 已包含第 2 节证书文件。

### 3.2 必填环境变量

以下变量缺失时，`docker-compose -f docker-compose.prod.yml config` 会 fail-fast：

```bash
export DOCKER_NAMESPACE=<dockerhub-user>
export DOCKER_TAG=v0.1.0
export DB_PASSWORD='<strong-postgres-password>'
export GRAFANA_PASSWORD='<strong-grafana-password>'
export GRPC_TRUSTSTORE_PASSWORD='changeit'
```

`DOCKER_TAG` 可省略，默认 `v0.1.0`。其他变量必须显式注入。

Docker Compose 会自动读取仓库根目录的 `.env`。做 fail-fast 负向验证时，如需排除本地 `.env` 干扰，可使用空 env 文件：

```bash
touch /tmp/hyperscale-lcm-empty.env
env -u DB_PASSWORD -u GRAFANA_PASSWORD -u GRPC_TRUSTSTORE_PASSWORD -u DOCKER_NAMESPACE \
  docker-compose --env-file /tmp/hyperscale-lcm-empty.env -f docker-compose.prod.yml config
```

### 3.3 启动

```bash
docker-compose -f docker-compose.prod.yml config
docker-compose -f docker-compose.prod.yml up -d
docker-compose -f docker-compose.prod.yml ps
```

`lcm-satellite` 是单机全栈演示形态：它通过 `LCM_CORE_ADDR=lcm-core:8080` 连接 Core，并挂载宿主机 `/var/run/docker.sock` 执行 Docker job。真实生产里，Satellite 应部署在被管节点侧；Kubernetes 路径使用 DaemonSet。

### 3.4 健康检查

```bash
curl -sf http://localhost:8080/health/ready
curl -sf http://localhost:8080/health/live
curl -sf http://localhost:9093/-/healthy
```

Core 就绪后，检查 Satellite 是否注册：

```bash
docker logs --tail 100 lcm-satellite
```

预期看到 `Registration Successful` 和周期性 heartbeat。

---

## 4. Helm 路径

### 4.1 前置条件

- Kubernetes 集群可用。
- `helm` 可访问 Bitnami chart repository。
- 已准备发布镜像，并知道 Docker Hub namespace。
- 已创建目标 namespace。

```bash
kubectl create namespace lcm
```

### 4.2 创建 mTLS Secret

```bash
kubectl -n lcm create secret generic lcm-tls \
  --from-file=ca.pem=certs/ca.pem \
  --from-file=server.pem=certs/server.pem \
  --from-file=server-pkcs8.key=certs/server-pkcs8.key \
  --from-file=client.pem=certs/client.pem \
  --from-file=client.key=certs/client.key \
  --from-file=truststore.jks=certs/truststore.jks \
  --from-literal=truststore-password=changeit
```

如果使用 chart-managed Secret，必须设置 `security.mtls.createSecret=true` 并在 values 中提供所有证书内容；不建议把真实生产私钥写入普通 values 文件。

### 4.3 release values

创建 `release-values.yaml`：

```yaml
global:
  imageRegistry: docker.io/<dockerhub-user>

core:
  image:
    tag: v0.1.0

frontend:
  image:
    tag: v0.1.0

satellite:
  image:
    tag: v0.1.0
  core:
    grpcPort: 8080

security:
  mtls:
    enabled: true
    secretName: lcm-tls

postgresql:
  auth:
    password: "<strong-postgres-password>"

kafka:
  enabled: true

monitoring:
  alertmanager:
    enabled: true
```

内置 Redis 默认关闭 auth，以匹配当前 Core 的 `REDIS_URL` 连接方式。需要 Redis auth 时，使用外部 Redis 并通过 Core env 显式注入带凭据的 `REDIS_URL`。

### 4.4 安装

```bash
helm dependency build helm/hyperscale-lcm
helm lint helm/hyperscale-lcm -f release-values.yaml
helm template lcm helm/hyperscale-lcm -n lcm -f release-values.yaml >/tmp/hyperscale-lcm-rendered.yaml
helm upgrade --install lcm helm/hyperscale-lcm -n lcm -f release-values.yaml
```

### 4.5 健康检查

```bash
kubectl -n lcm rollout status deploy/lcm-core
kubectl -n lcm rollout status deploy/lcm-frontend
kubectl -n lcm rollout status daemonset/lcm-satellite

kubectl -n lcm port-forward svc/lcm-core 8080:8080 &
curl -sf http://localhost:8080/health/ready
```

Satellite 注册检查：

```bash
kubectl -n lcm logs daemonset/lcm-satellite --tail=100
```

---

## 5. 主流程验收

### 5.1 部署与主流程基线

- [ ] 仅凭本 runbook 完成部署，未读源码、未咨询作者。
- [ ] Core gRPC 以 mTLS 启动，Satellite 注册成功。
- [ ] 前端登录成功。
- [ ] 发现并纳管 mock 设备。
- [ ] 提交 Job 并看到执行回调。
- [ ] AlertManager UI 可打开，路由配置可见。

### 5.2 重启恢复

compose：

```bash
docker-compose -f docker-compose.prod.yml restart lcm-core
docker-compose -f docker-compose.prod.yml restart lcm-satellite
docker-compose -f docker-compose.prod.yml down
docker-compose -f docker-compose.prod.yml up -d
```

Kubernetes：

```bash
kubectl -n lcm delete pod -l app.kubernetes.io/name=hyperscale-lcm-core
kubectl -n lcm delete pod -l app.kubernetes.io/name=hyperscale-lcm-satellite
```

验收项：

- [ ] 重启 Core 后，Satellite 自动重连并恢复 heartbeat。
- [ ] 重启 Satellite 后，在线状态在一个 heartbeat 周期内恢复。
- [ ] 整套重启后，PostgreSQL 中设备池、Job 历史、回调记录、审计日志仍在。
- [ ] 缺失 `DB_PASSWORD`、`GRAFANA_PASSWORD` 或 `GRPC_TRUSTSTORE_PASSWORD` 时，compose 配置阶段失败并提示缺失变量。

### 5.3 验收记录

| 字段 | 记录 |
|------|------|
| 日期 |  |
| 环境 | compose / k8s |
| 镜像版本 | v0.1.0 |
| 执行人 |  |
| 主流程结果 |  |
| 重启恢复结果 |  |
| 修正项 |  |
