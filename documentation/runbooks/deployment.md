# Deployment 运维手册 (Runbook)

> **Last Updated:** 2026-06-12
> **Scope:** 从固定版本镜像部署 Hyperscale LCM 的 Core、Satellite、Frontend 与基础依赖；覆盖 docker-compose 单机路径、Helm 路径、mTLS Secret、必填环境变量、健康检查与验收记录。离线或受限网络环境先按 [offline-deployment.md](offline-deployment.md) 准备 release bundle。

---

## 1. 部署边界

本 runbook 面向无真实 BMC / 裸机 / 外部 AlertManager secret 的环境。可验证目标是：

- Core 以 `prod` profile 启动，gRPC mTLS `client-auth=required`。
- Satellite 使用 mTLS 注册到 Core。
- Frontend 可登录并访问 Core API。
- Kafka / Redis / PostgreSQL / AlertManager 路由可见。
- 重启后 PostgreSQL 持久化数据仍可恢复。

Core 的 gRPC 与 HTTP 共用同一 Quarkus 服务器，prod profile 下双端口：`8080` 为明文 REST/health/metrics（集群内，前端与探针使用），`8443` 为 HTTPS + 强制客户端证书（mTLS）。Satellite 的 `LCM_CORE_ADDR` 必须指向 `core-host:8443`，不要使用历史残留的 `:9000`，也不要经明文 `8080` 走 gRPC；明文 `8080` 上的 gRPC 请求由 `GrpcTlsEnforcer`（`lcm.grpc.require-tls=true`）在进入 handler 前以 `PERMISSION_DENIED` 拒绝，Compose 仅把 `8080` 绑定到宿主回环地址，网络策略/防火墙仅作补充。

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

> 隔离默认值：Satellite 默认**不**启动 PXE（TFTP :69、HTTP :8090、ProxyDHCP :4011）和网络发现（DHCP 监听、ARP 扫描）。helm/k8s 的 Satellite 使用 `hostNetwork`，这些功能一旦开启会直接占用宿主网络；只在需要的节点上设 `LCM_PXE_ENABLED=true` / `LCM_DISCOVERY_ENABLED=true`，详见 [pxe.md](pxe.md)。

### 2.3 Satellite 身份（每个 Satellite 一张证书）

Core 以 TLS 握手验证过的**客户端证书指纹（SHA-256）**识别 Satellite，不再只信任自报 IP：

- 首次注册：设备须已被批准（APPROVED/MANAGED），仍按请求 IP 匹配设备，匹配成功后把证书指纹绑定到该设备，并记录到 `satellite.cert_fingerprint`。
- 之后同一证书重连：不再依赖 IP——容器 IP 变化后仍识别为同一设备，复用同一个 Satellite ID，并更新记录的 IP。
- 其他证书声称已绑定设备的 IP，或绑定设备被拒绝/退回非批准状态：注册被拒（`UNAUTHENTICATED`）。
- 所以每个 Satellite 必须用各自的证书；多个 Satellite 共用 `client.pem` 会被视为同一身份。用现有 CA 为新 Satellite 签发：

```bash
./scripts/issue_satellite_cert.sh <name>   # 输出 certs/satellites/<name>/{client.pem,client.key,ca.pem} 与指纹
```

把该目录作为该 Satellite 的 `LCM_CERTS_DIR`。尚未做：证书吊销/轮换。拒绝绑定设备会阻止后续注册，但不会主动断开已有连接。清除 `satellite_cert_fingerprint` 只会解除绑定，不会吊销证书；设备仍获批准时，旧证书可再次绑定，不能用清除字段代替撤销准入。

---

## 3. docker-compose 单机路径

### 3.1 前置条件

- Linux 主机已安装 Docker 与 Docker Compose。Docker Engine 29 与 `docker-compose` v1.29.x 不兼容（重建已有容器时报 `KeyError: 'ContainerConfig'`），请使用 Compose v2 插件（本文命令均为 `docker compose`；`scripts/check_compose_deployment_preflight.sh` 优先使用 v2，仅在没有 v2 时回退到 `docker-compose`）。
- **项目名**：同目录下若已有其他 LCM compose 环境，务必加 `-p <独立项目名>`，否则 compose 会把同名服务的既有容器当作本栈的并重建；单机实验环境见 [lab-quickstart.md](lab-quickstart.md)。
- **隔离前提**：`docker-compose.prod.yml` 写死了全局容器名（`lcm-core`、`lcm-postgres`、`lcm-redis`、`lcm-kafka`、`lcm-grafana` 等）。`-p <项目名>` 只隔离网络和卷，不能让两套 prod 实例同机共存；启动前用 `docker ps -a --format '{{.Names}}' | grep '^lcm-'` 确认无同名容器，并确认清单实际发布的全部宿主端口（见 `docker compose -f docker-compose.prod.yml config` 的 `ports`）未被占用。已有同名容器或端口冲突时，改用独立 Docker 主机/daemon，不要删除已有容器。同机已有 dev 环境（项目 `hyperscale-lcm`、卷、端口 6379 等）时，同样加 `-p` 并先核对端口，避免重建或停掉 dev 容器、误挂 dev 卷。
- **版本对应**：8443 双端口 mTLS 契约（Core 配置、`GrpcTlsEnforcer`、各部署清单）只存在于 v0.1.1 的源码中；已发布的 v0.1.0 镜像仍是旧契约（8080 明文 gRPC 口径，且 prod 无法启动），不能与本文清单搭配。v0.1.1 发布前，必须用本源码本地构建（`scripts/build.sh v0.1.1`）；验收记录须写镜像 ID 与构建所用 commit/dirty diff，不能只写 tag。
- 已准备并可拉取三个固定版本镜像（v0.1.1 发布前需按上条本地构建）：
  - `<dockerhub-user>/lcm-core:v0.1.1`
  - `<dockerhub-user>/lcm-satellite:v0.1.1`
  - `<dockerhub-user>/lcm-frontend:v0.1.1`
- 如需在本机为验收临时构建应用镜像，使用 `DOCKER_NAMESPACE=<dockerhub-user> scripts/build.sh v0.1.1`；该路径仍需要访问基础镜像与构建依赖 registry。
- compose 基础依赖镜像使用固定 tag；受限网络环境应通过离线包导入，不依赖 `latest` 漂移。
- 若目标主机无法直接访问 Docker Hub，先在联网制包机上执行 [offline-deployment.md](offline-deployment.md)，并在目标主机导入 bundle 镜像。
- 当前目录是仓库根目录，且 `certs/` 已包含第 2 节证书文件。

M2 批 B（V2.10.0）升级顺序：冻结新任务提交 → 停止旧 Core → 按 [upgrade-and-backup.md](upgrade-and-backup.md) 备份数据库并保存活动作业清单 → 启动新版执行迁移。旧 SCHEDULED/RUNNING 将保守转为 UNKNOWN，不能根据新加列为空推断“从未执行”；逐项核对目标后再 resolve，禁止批量重置为 SCHEDULED 或自动重发。不要与旧版 Core 混跑。回退旧二进制前，先冻结提交并通过新版核对 UNKNOWN；停止新版 Core 后再次确认 `SELECT count(*) FROM job WHERE status = 'UNKNOWN'` 为 0，才启动旧版；若从备份恢复，还必须核对备份后已经发生的远端副作用。仅回退代码不能撤销迁移后的状态变化，新列可保留。未发布分支中已经执行过旧版 V2.10.0 的实验库应保留原库，另建隔离实验库验证修订迁移；不要自动 repair 共享库的迁移校验和。

### 3.2 必填环境变量

以下变量缺失时，`docker compose -f docker-compose.prod.yml config` 会 fail-fast：

```bash
export DOCKER_NAMESPACE=<dockerhub-user>
export DOCKER_TAG=v0.1.1
export DB_PASSWORD='<strong-postgres-password>'
export GRAFANA_PASSWORD='<strong-grafana-password>'
export GRPC_TRUSTSTORE_PASSWORD='changeit'
```

`DOCKER_TAG` 可省略，默认 `v0.1.1`。其他变量必须显式注入。

Docker Compose 会自动读取仓库根目录的 `.env`。做 fail-fast 负向验证时，如需排除本地 `.env` 干扰，可使用空 env 文件：

```bash
touch /tmp/hyperscale-lcm-empty.env
env -u DB_PASSWORD -u GRAFANA_PASSWORD -u GRPC_TRUSTSTORE_PASSWORD -u DOCKER_NAMESPACE \
  docker compose --env-file /tmp/hyperscale-lcm-empty.env -f docker-compose.prod.yml config
```

### 3.3 启动

启动前先执行非破坏性 preflight；该脚本只校验配置、证书、固定镜像 tag 与本地镜像存在性，不会启动或停止容器：

```bash
scripts/check_compose_deployment_preflight.sh --namespace "$DOCKER_NAMESPACE" --tag "$DOCKER_TAG"
```

如果当前主机可访问 registry、尚未预拉取镜像，且只想先验证配置契约，可临时跳过本地镜像检查：

```bash
scripts/check_compose_deployment_preflight.sh --namespace "$DOCKER_NAMESPACE" --tag "$DOCKER_TAG" --skip-image-check
```

```bash
docker compose -f docker-compose.prod.yml config
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml ps
```

`lcm-satellite` 是单机全栈演示形态：它通过 `LCM_CORE_ADDR=lcm-core:8443` 连接 Core，并挂载宿主机 `/var/run/docker.sock` 执行 Docker job。真实生产里，Satellite 应部署在被管节点侧；Kubernetes 路径使用 DaemonSet。

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

### 3.5 SSH 只读任务（实验，Satellite 侧）

Satellite 镜像包含 `bash` 与 `openssh-client`。SSH 任务（payload 含 `task`）只在 Satellite 本地取凭据，不经 Core：

| 变量 | 默认 | 说明 |
|------|------|------|
| `LCM_SSH_KEYS_DIR` | `/app/ssh/keys` | 私钥目录（只读挂载）；任务里的 `keyRef` 只能是该目录下的文件名 |
| `LCM_SSH_KNOWN_HOSTS` | `/app/ssh/known_hosts` | known_hosts 文件；缺失或主机密钥不符即失败（`StrictHostKeyChecking=yes`） |
| `LCM_SSH_TIMEOUT` | `60s` | 超时/取消只终止本端 ssh 客户端，输出管道排空最多再等 1s；远端命令状态视为未知 |
| `LCM_DOCKER_TIMEOUT` | `1h` | 单个 Docker 作业从拉取到退出的执行上限；超时后请求停止并删除容器，清理另有独立上限。删除失败保留 RUNNING，等待 Core 变 UNKNOWN 后人工核对容器与占用。注意：Satellite 内置 Docker 客户端 API 版本为 1.41，新版 daemon（最低 1.44）会拒绝，需设 `DOCKER_API_VERSION=1.44`（升级客户端依赖为后续项） |
| `LCM_SSH_ALLOW_INLINE` | 未设置 | 仅 dev/test：允许旧的内联口令/私钥 payload；默认拒绝 |

目前任务目录只有 `SYSTEM_INFO`（`uname -a && uptime && df -h / && free -m`，任一步失败即停止并返回非零），命令固定在 Satellite 内，输出上限 64KiB。Core 侧：提交作业时 `executionType=SSH`，带 `targetDeviceId`（必须是已批准/已纳管的设备）与 `task`，Core 用设备 IP 和配置的 `lcm.ssh.default-user` / `lcm.ssh.default-key-ref`（环境变量 `LCM_SSH_DEFAULT_USER` / `LCM_SSH_DEFAULT_KEY_REF`）在服务端构造 payload，不含任何凭据；未配置时返回 503。旧的内联口令/私钥 payload 默认返回 400，仅 dev/test 可用 `LCM_SSH_INLINE_PAYLOAD_ENABLED=true` 开启。作业记录 `targetDeviceId`/`targetHost`，命令输出写入 `resultOutput`（有长度上限），提交动作写入审计（操作人、目标、任务）。

---

## 4. Helm 路径

### 4.1 前置条件

- Kubernetes 集群可用。
- `helm` 可访问 Bitnami chart repository。
- 已准备发布镜像，并知道 Docker Hub namespace。
- 已创建目标 namespace。
- 若目标环境无法访问 Bitnami chart repository 或镜像 registry，先按 [offline-deployment.md](offline-deployment.md) 准备 Helm chart 依赖与镜像包。

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
    tag: v0.1.1

frontend:
  image:
    tag: v0.1.1

satellite:
  image:
    tag: v0.1.1
  core:
    grpcPort: 8443

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
docker compose -f docker-compose.prod.yml restart lcm-core
docker compose -f docker-compose.prod.yml restart lcm-satellite
docker compose -f docker-compose.prod.yml down
docker compose -f docker-compose.prod.yml up -d
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
| 镜像版本 | v0.1.1（未发布，本地构建；记录镜像 ID 与源码 commit/diff） |
| 执行人 |  |
| 主流程结果 |  |
| 重启恢复结果 |  |
| 修正项 |  |

#### 2026-10-10 部分通过（AI 执行，不关闭 Step 5）

偏差：非干净主机（已有 dev 环境、`.env`、`certs/`）；镜像从 main@9a43f8e 本地构建，非已发布的 `v0.1.0`；使用 `-p lcm-accept` 与 Redis 宿主端口改 16379 的 compose 副本；开启 dev 用户并放行 `/api/auth/*`（prod profile 无登录入口，见下）。

已通过：四项必填变量缺失 fail-fast；preflight；修复后 Core 双端口启动，无客户端证书访问 8443 被拒、带证书 200；Satellite 经 8443 mTLS 注册（`Registration Successful`）并持续 heartbeat；命令下发到 Satellite；重启 Core 后命令流自动重连；整套 down/up 后 PostgreSQL 中 Job 与设备仍在。

本次修复：Core prod 配置（`insecure-requests=redirect` 无证书导致无法启动、gRPC mTLS 实际为明文）、Grafana 重复默认数据源、frontend 容器 healthcheck（`localhost` 解析到 IPv6 被拒）。

未通过/待跟进：(1) prod profile 下 `/api/auth/*` 需认证且 dev 用户默认关闭，无法登录，缺生产认证入口；(2) Satellite 注册按容器 IP 匹配已批准设备，整套重启后 IP 变化导致注册被拒；(3) SHELL Job 在 Satellite 容器内执行 FAILED（exit -1），且 Core 处理回调时报 `Illegal pop() with non-matching JdbcValuesSourceProcess` 并路由 DLQ；(4) 前端登录、AlertManager UI 路由内容、三级重启中 Satellite 单独重启尚未逐项确认。

补充验证（同日，HEAD 9a43f8e + 未提交修复，样本见 `.local/m0-baseline.patch`）：

- 8443 TLS 层拒绝证据（curl -v）：无客户端证书 → `tlsv13 alert certificate required`；非受信 CA 签发的证书 → `alert certificate unknown`；受信证书 → `/health/ready` 200。明文 8080 上的 gRPC 请求 → `grpc-status: 7`（`GrpcTlsEnforcer`，进入 handler 前拒绝）。
- Satellite 校验服务端证书：`tls.Config` 未跳过校验，`ServerName` 固定为 `localhost`（匹配 `generate_keys.sh` 的 SAN），因此不依赖 `lcm-core` 主机名；代价是任何 SAN 含 `localhost` 的服务端证书都会被接受。
- Core 完整矩阵（CI_CONTRACT §4.1 环境，真实 PostgreSQL/Redis/Kafka，v0.1.1 本地构建源码）：`./gradlew check --no-daemon` exit 0，161 个测试 0 失败 0 跳过，`jacocoTestCoverageVerification` 通过，`GrpcTlsEnforcerTest` 2/2 实际执行（日志 `.local/gradle-check4.log`）。此前一轮出现的 `E2EIntegrationTest` 超时在随后两次完整运行中未复现，根因未确定（怀疑首次运行的 Kafka topic 创建时序），未调大超时。环境陷阱：prod compose 会在宿主发布 5432，若同时运行，Core 测试会因数据库认证失败而误报，测试前必须停掉。
- Satellite `go test ./... -count=1`、Frontend `npm test && npm run lint && npm run build`、`check_ci_contract.sh` 通过。
- 本地 `scripts/ci_demo_smoke.sh`（`LCM_GRPC_REQUIRE_TLS=false` 覆盖，独立 `COMPOSE_PROJECT_NAME`）通过，Job 以 exit 0 COMPLETED；`ci.yml` load-test 步骤在本地等价复现通过（20 卫星×10 连接，30s：注册 200/200，心跳 5564/5564，0 失败）；GitHub CI 上的这两个 job 尚未运行。
- 原始探针证据（`.local/probe-evidence.txt`，v0.1.1 本地构建，Core 镜像 `sha256:bae55d9f…`）：grpcurl 明文 8080 → `PermissionDenied: gRPC requires TLS with a client certificate`；grpcurl 受信证书 8443 + 不存在的 Satellite ID → handler 返回 `NotFound`；无证书/非受信 CA 的 TLS alert 见上；REST live/ready/metrics 均 200。
- Compose v2（v2.29.7 插件）实测：必填变量缺失负向渲染 exit 15 并指明变量；正向渲染 Satellite 目标 `lcm-core:8443`；preflight 在 v2 下通过，且隐藏 `docker-compose` v1 后仍通过。
- Satellite 运行镜像缺少 `bash` 与 `ssh`（`command -v` 均为空），这是走查中 SHELL Job 返回 exit -1 的直接原因；SSH 执行方式在该镜像内也不可用。
- 真实后端浏览器验收（SSH 任务，非 API mock）：`frontend/playwright.real.config.ts` + `frontend/e2e-real/ssh-task.real.spec.ts`，不属于默认 `npx playwright test`（mock 套件仍在 `frontend/e2e`）。前置：Core(含 `LCM_SSH_DEFAULT_USER`/`LCM_SSH_DEFAULT_KEY_REF`、dev 登录、`ALLOWED_ORIGINS` 含 `http://127.0.0.1:5173`)、Satellite（其自身 IP 对应的设备已批准）与带实验密钥的 sshd；运行 `LCM_REAL_TARGET_IP=<sshd IP> npx playwright test -c playwright.real.config.ts`。流程：登录 → Discovery 页批准目标（PENDING 时）→ Dashboard 选 SSH Maintenance + 已批准设备 → 作业详情页出现 Completed 与真实 `uname/df/free` 输出。本地一次通过（40s，目标为 Satellite 同机实验 sshd），库内审计为 JOB_SUBMITTED(admin)、JOB_SCHEDULED、JOB_COMPLETED。未纳入 GitHub CI（需要真实栈）。
- Satellite 证书身份（M1 批 3，v0.1.1 本地构建，真实 mTLS + 真实 PostgreSQL，grpcurl 以两张 CA 签发的证书实测）：alpha 首次注册绑定批准设备；alpha 换 IP 再注册 → 同一 `assignedId`，设备 IP 更新；beta 声称 alpha 的 IP → `Device is bound to a different Satellite certificate`；beta 使用未知 IP / PENDING 设备 → 拒绝；客户端伪造 `x-lcm-peer-fingerprint` 头无效（仍按握手证书识别）；无客户端证书在 TLS 层失败，8080 明文 gRPC → PermissionDenied。Core `gradlew check` 172 测试通过。
- 单机实验环境干净启动复审（2026-10-15，PR #31：head `48a9a2e` 加本 PR 的准备顺序、私钥权限和 bootstrap 复用修复；本地构建镜像 ID core `e335699d38ef` / satellite `2e986bb49fe2` / frontend `7ad6503e9d19` / sshd `f1f61c1fdb69`）：从无 JWT 资源、证书与 lab/data 的源码副本先准备再构建，再以空卷启动。审核项目名 `pr31-review`，仅将实验 Redis 的宿主端口改为 16379、sshd 镜像改为独立审核命名空间，以保留既有开发服务；其余按 [lab-quickstart.md](lab-quickstart.md)。readiness 与登录均为 200；Satellite 以独立证书注册，PXE/发现默认关闭，容器未监听 69/8090/4011；部署 nginx 前端的真实 Playwright 1/1 通过（6.7s）：登录 → 批准 `172.30.77.30` → SYSTEM_INFO → Completed，真实系统输出、退出码 0；库内包含 JOB_SUBMITTED(admin)/SCHEDULED/COMPLETED 审计。bootstrap 重复执行与后续 --approve-target 均成功，已有 REJECTED 目标明确拒绝且不改状态；准备脚本重复执行不改实验文件，私钥均为 0600。重启 Satellite 后同证书重连、assignedId 不变。Core 185 项、Satellite 全量、Frontend 31 项及 lint/build 均通过。未覆盖：Core 重启后的作业核对与完成事件去重（M2）、生产安全、真机/BMC、k8s；不关闭 Deployment Closure Step 5。
- 持久派发真实栈验证（M2 批 B，lab 栈 + 缩短超时 `LCM_JOBS_*`：扫描 5s、补发宽限 10s、未送达 70s、起跑 60s）：在线基线作业 COMPLETED 且 `dispatch_attempt=1`；停掉 Satellite 后提交 → 保持 SCHEDULED（attempt=0），恢复后被补发并 COMPLETED（只发一次）；停掉超过期限 → FAILED（"never sent"，attempt=0，Satellite 重启后状态不变）；暂停 Satellite 进程（命令流仍在）后提交 → 命令已认领但无回报，60s 后 UNKNOWN 且不重发，解除暂停后迟到的真实结果把它收口为 COMPLETED；另一例 UNKNOWN 经 `POST /api/jobs/{id}/resolve` 记为 FAILED，随后到达的 COMPLETED 被忽略并写 `late_result_ignored` 审计（该例中 Satellite 实际执行了命令，人工结论与事实不符——说明 resolve 是操作者责任，冲突证据只在审计里）。取消 UNKNOWN 返回 409。
- PR #33 复审补充（head `65a36f5` 加迁移/认领/取消/审计修复，基线旧 Core `cad528b`）：独立 PG/Redis/Kafka；旧 Core 建库至 2.9.0、备份并恢复到另一个实验库、新 Core 实际迁移至 2.10.0，旧活动作业转 UNKNOWN，不增加目标执行记录。受控 sshd 每次 SYSTEM_INFO 写独立 UUID：重复 Kafka 消息+目标变化后仍只执行一次；离线恢复补发一次；离线超期 FAILED、零执行；已认领取消 409；暂停 Satellite 后 UNKNOWN 不重发，迟到结果收口；真实浏览器显示 UNKNOWN；人工核对后到达的冲突结果保留为 JOB_LATE_RESULT。Core 236 项、Frontend 32 项及 lint/build、Satellite 全量通过。歧义 emit 抛错由命令流故障注入回归验证，不把它当作真实网络半写验收；不关闭批 C/D、生产或硬件验收。
- 恢复与容量真实栈验收（M2 批 D / M3，lab 栈，源码 = PR #34 分支，本地构建镜像 core dbb10a4fea34 satellite 0ee21aeb8ec4，缩短超时：扫描 5s、PENDING 重试 10s、PENDING 上限 100s、起跑 90s、运行上限 60s；Satellite 为 16 核，库内 node 规格 16 核/23GB，作业均为 SHELL，受控命令向 Satellite 容器内文件追加记录以数执行次数）：(1) 容量：同时提交两个 16 核作业，一个先放置，另一个保持 PENDING，先行者结束后才被重试放置，两条执行记录相隔 32s、没有重叠，两者均 COMPLETED；(2) Core 重启：作业 RUNNING 时停止 Core 40s，期间作业在 Satellite 完成，终态发送失败（`EOF`）后入待补报队列，Core 恢复、命令流重连后 Satellite 日志出现 `Replaying terminal status`，作业 COMPLETED，记录文件仅 1 条（未重复执行）；(3) PostgreSQL 停止时提交 → HTTP 500，库内没有该作业；(4) Kafka 停止时提交 → 作业 SCHEDULED，sweeper 补发（attempt=1），Kafka 恢复前状态停在 SCHEDULED，恢复后回调到达 COMPLETED，Kafka 里滞后的调度消息被拒（`already dispatched`），执行只发生一次；(5) Satellite 容器重启中断 120s 作业 → 状态保持 RUNNING 直到运行上限，之后变 UNKNOWN（原因可见），不自动重发；(6) 需要 64 核的作业 → PENDING 重试，100s 后 FAILED，`attempt=0`、原因"never dispatched and nothing ran"；(7) `pg_dump` 备份恢复到独立 PG：job 6/6、audit_log 17/17、satellite、node、discovered_devices 行数一致，作业状态分布一致（COMPLETED=4、FAILED=1、UNKNOWN=1）。(8) 整套栈 `down`（保留卷）再 `up`：作业状态分布不变（COMPLETED=5、FAILED=1、UNKNOWN=1）、Satellite 仍为 1 条（同证书同 ID），重启后新提交的作业 COMPLETED（节点规格由启动时数据库恢复，未依赖注册缓存）。外部副作用不随备份回滚，恢复后先核对再决定是否新建作业。同时发现并修复：`MockNodeSpecsProvider` 作为全局启用的 CDI alternative 覆盖了真实规格（此前所有 profile 的调度都按固定 32 核）。未覆盖：Docker 作业经 lab 栈的端到端（Satellite 容器未挂载 Docker socket，仅在宿主直连真实 Docker 验证过）、Satellite 重启后的去重账本（内存，重启即丢）、多 Core 副本、真机。
- PR #34 审核补充（2026-10-16，固定 head 64420e7 加租户/预留/资源/Docker 修复）：独立 PostgreSQL/Redis/Kafka、native Core/Satellite、真实 mTLS 与 Docker daemon。Docker 退出 3 经 REST→调度→Kafka→Satellite→daemon→回调持久化 FAILED/exit 3；直连 daemon 另验退出 0、执行上限与尾部 64KiB。整节点作业排队不重叠、Core 停机期间终态入队后重连补报且执行一次、启动从 node 恢复规格、外租户详情/状态/统计/取消/核对隔离、PENDING 重试到期 FAILED 且 attempt=0、独立 pg_dump 恢复状态分布一致均通过。Core 257 项、Frontend 32 项及 lint/build、Satellite 全量和变更模块 race 通过。Docker 删除失败的保留占用路径由故障注入回归验证，未声称真实 daemon 拒绝清理验收；API 1.44 仍需文档中的环境变量，不关闭 M4、生产或硬件验收。
- 未验证：k8s 清单在真实集群上的行为；GitHub CI 全链路。
