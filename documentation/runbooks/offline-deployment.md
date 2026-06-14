# Offline Deployment Bundle Runbook

> **Last Updated:** 2026-06-14
> **Scope:** 为无法直接访问 Docker Hub / Bitnami chart repository 的干净验收主机准备 Hyperscale LCM 固定版本部署包。本文只解决镜像与 Helm 依赖搬运；实际部署和验收仍以 [deployment.md](deployment.md) 为准。

---

## 1. 使用场景

当目标主机满足以下任一情况时，先按本文准备离线包：

- 不能访问 Docker Hub，无法拉取 `lcm-core` / `lcm-satellite` / `lcm-frontend`。
- 不能访问 Docker Hub 上的 compose 依赖镜像，例如 PostgreSQL、Redis、Kafka、Jaeger、Prometheus、AlertManager、Grafana。
- 不能访问 Bitnami Helm repository，无法执行 `helm dependency build`。

本文不覆盖真实 BMC、PXE 裸机网络、Slack / PagerDuty / 邮件 secret。这些仍属于外部门控项。

---

## 2. 联网制包机

### 2.1 前置条件

制包机需要具备：

- Docker，可访问 Docker Hub。
- Helm，可访问 `https://charts.bitnami.com/bitnami`。
- 已登录可拉取发布镜像的 registry。
- 当前目录为仓库根目录。

### 2.2 生成离线包

```bash
export DOCKER_NAMESPACE=<dockerhub-user>
export DOCKER_TAG=v0.1.0

scripts/prepare_offline_release_bundle.sh --namespace "$DOCKER_NAMESPACE" --tag "$DOCKER_TAG" --dry-run
scripts/prepare_offline_release_bundle.sh --namespace "$DOCKER_NAMESPACE" --tag "$DOCKER_TAG"
```

默认输出目录：

```text
.local/release-bundles/hyperscale-lcm-v0.1.0-<timestamp>/
```

包内包含：

| 文件 / 目录 | 用途 |
|-------------|------|
| `images/*.tar` | compose 单机路径所需的应用镜像和固定版本基础依赖镜像 |
| `helm-hyperscale-lcm-v0.1.0.tgz` | 含 `charts/` 依赖归档的 Helm chart 目录包 |
| `manifest.txt` | 镜像 namespace、tag、清单 |
| `manifest.env` | 目标主机可复用的 `DOCKER_NAMESPACE` / `DOCKER_TAG` |
| `SHA256SUMS` | 包内文件校验 |
| `load-images.sh` | 目标主机导入镜像脚本 |

如果制包机已经预拉取全部镜像，可禁用拉取：

```bash
scripts/prepare_offline_release_bundle.sh \
  --namespace "$DOCKER_NAMESPACE" \
  --tag "$DOCKER_TAG" \
  --no-pull
```

---

## 3. 传输到目标主机

把整个 bundle 目录复制到目标主机，例如：

```bash
scp -r .local/release-bundles/hyperscale-lcm-v0.1.0-<timestamp> user@target:/opt/
```

目标主机需要保留仓库工作目录，因为 compose 文件、证书脚本、Helm values 和 runbook 都来自仓库本身。

---

## 4. 目标主机导入

在目标主机上执行：

```bash
cd /opt/hyperscale-lcm-v0.1.0-<timestamp>
./load-images.sh
```

`load-images.sh` 会先校验 `SHA256SUMS`，再逐个执行 `docker load`。

导入后确认应用镜像存在：

```bash
docker images | grep 'lcm-'
```

---

## 5. compose 验收路径

回到仓库根目录，按 [deployment.md §2](deployment.md#2-mtls-证书材料) 生成或放置证书，再按 [deployment.md §3](deployment.md#3-docker-compose-单机路径) 执行。

关键环境变量必须与制包时一致：

```bash
source /opt/hyperscale-lcm-v0.1.0-<timestamp>/manifest.env
export DB_PASSWORD='<strong-postgres-password>'
export GRAFANA_PASSWORD='<strong-grafana-password>'
export GRPC_TRUSTSTORE_PASSWORD='changeit'
```

先执行 preflight，确认 bundle 中的镜像、证书与 compose 契约齐备：

```bash
scripts/check_compose_deployment_preflight.sh --namespace "$DOCKER_NAMESPACE" --tag "$DOCKER_TAG"
```

然后执行：

```bash
docker-compose -f docker-compose.prod.yml config
docker-compose -f docker-compose.prod.yml up -d
docker-compose -f docker-compose.prod.yml ps
```

后续主流程与重启恢复 checklist 继续使用 [deployment.md §5](deployment.md#5-主流程验收)。

---

## 6. Helm 验收路径

在目标主机解包 Helm chart：

```bash
mkdir -p /tmp/hyperscale-lcm-chart
tar -xzf /opt/hyperscale-lcm-v0.1.0-<timestamp>/helm-hyperscale-lcm-v0.1.0.tgz \
  -C /tmp/hyperscale-lcm-chart
```

确认依赖归档已随包携带：

```bash
find /tmp/hyperscale-lcm-chart/hyperscale-lcm/charts -maxdepth 1 -name '*.tgz' -print
```

创建 `release-values.yaml` 时，`global.imageRegistry` 必须指向本地已导入镜像所在的 registry namespace。如果目标 k8s 节点不能直接使用 Docker 本地镜像，需要先把 `images/*.tar` 导入到集群节点或推送到集群可访问的私有 registry。

示例：

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

security:
  mtls:
    enabled: true
    secretName: lcm-tls
```

然后继续执行 [deployment.md §4](deployment.md#4-helm-路径) 中的 Secret 创建、`helm lint`、`helm template` 和 `helm upgrade --install`。

---

## 7. 常见失败

| 现象 | 判断 | 处理 |
|------|------|------|
| `docker-compose ... config` 提示 `DOCKER_NAMESPACE is required` | 目标主机未设置 namespace | `source <bundle>/manifest.env`，或手工设置与制包一致的 `DOCKER_NAMESPACE` |
| `pull access denied` 或 `manifest unknown` | 目标主机仍尝试远程拉镜像，或 tag 不一致 | 确认 `docker images` 中存在对应镜像，且 `DOCKER_TAG` 与制包 tag 一致 |
| `helm dependency build` 仍尝试联网 | 目标主机使用了原 chart 目录而不是 bundle 中的 chart | 使用 bundle 里的 `helm-hyperscale-lcm-*.tgz` 解包目录 |
| k8s Pod `ImagePullBackOff` | 集群节点拿不到镜像 | 将 bundle 镜像导入每个节点，或推送到集群可访问的私有 registry |
| Satellite 无法注册 | mTLS 证书或 `LCM_CORE_ADDR` 不匹配 | 复核 [deployment.md §2](deployment.md#2-mtls-证书材料) 和 `LCM_CORE_ADDR=core-host:8080` |

---

## 8. 验收记录

完成离线包导入后，不在本文重复记录主流程结果。请把最终验收结果回填到 [deployment.md §5.3](deployment.md#53-验收记录)，并注明：

- 使用的 bundle 目录名。
- `manifest.env` 中的 `DOCKER_NAMESPACE` / `DOCKER_TAG`。
- compose 或 Helm 路径。
- 是否使用私有 registry。
