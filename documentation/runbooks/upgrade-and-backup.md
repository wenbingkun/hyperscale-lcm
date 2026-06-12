# Upgrade And Backup 运维手册 (Runbook)

> **Last Updated:** 2026-06-12
> **Scope:** Hyperscale LCM 的版本升级、Flyway 迁移、PostgreSQL 备份恢复、mTLS 证书轮换与 JWT key 轮换口径。

---

## 1. 升级前检查

升级前必须确认：

- 已记录当前镜像版本和目标镜像版本。
- 已完成 PostgreSQL 备份，并确认备份文件可读取。
- 已渲染 Helm 或 compose 配置，确认必填 secret 存在。
- 已阅读目标版本 CHANGELOG。
- 目标版本没有要求人工 SQL 回填或外部依赖变更。

compose：

```bash
docker-compose -f docker-compose.prod.yml config
```

Helm：

```bash
helm dependency build helm/hyperscale-lcm
helm lint helm/hyperscale-lcm -f release-values.yaml
helm template lcm helm/hyperscale-lcm -n lcm -f release-values.yaml >/tmp/hyperscale-lcm-rendered.yaml
```

---

## 2. Flyway 迁移升级

Core 启动时执行 Flyway migration：

```properties
quarkus.flyway.migrate-at-start=true
```

升级流程：

1. 停止会产生新 Job 的外部入口或维护窗口公告。
2. 执行 PostgreSQL 备份。
3. 升级 Core 镜像到目标版本。
4. 观察 Core 启动日志，确认 Flyway migration 成功。
5. 执行健康检查与主流程冒烟。

compose：

```bash
export DOCKER_TAG=v0.1.0
docker-compose -f docker-compose.prod.yml pull lcm-core lcm-frontend lcm-satellite
docker-compose -f docker-compose.prod.yml up -d
docker logs --tail 200 lcm-core
curl -sf http://localhost:8080/health/ready
```

Helm：

```bash
helm upgrade lcm helm/hyperscale-lcm -n lcm -f release-values.yaml
kubectl -n lcm rollout status deploy/lcm-core
kubectl -n lcm logs deploy/lcm-core --tail=200
```

回退口径：

- 仅回退镜像不能回退已执行的数据库迁移。
- 如果迁移已经改变 schema，必须先恢复升级前数据库备份，再回退到旧镜像。
- 禁止在未知 schema 状态下反复启动不同版本 Core。

---

## 3. PostgreSQL 备份

### 3.1 compose 备份

```bash
BACKUP_FILE="lcm-$(date +%Y%m%d-%H%M%S).dump"
docker exec lcm-postgres pg_dump \
  -U lcm_user \
  -d lcm_db \
  -F c \
  -f "/tmp/${BACKUP_FILE}"
docker cp "lcm-postgres:/tmp/${BACKUP_FILE}" "./${BACKUP_FILE}"
ls -lh "./${BACKUP_FILE}"
```

### 3.2 Helm 备份

先定位 PostgreSQL primary Pod：

```bash
PG_POD=$(kubectl -n lcm get pod \
  -l app.kubernetes.io/name=postgresql,app.kubernetes.io/component=primary \
  -o jsonpath='{.items[0].metadata.name}')
```

执行备份：

```bash
BACKUP_FILE="lcm-$(date +%Y%m%d-%H%M%S).dump"
kubectl -n lcm exec "$PG_POD" -- pg_dump \
  -U lcm \
  -d lcm \
  -F c \
  -f "/tmp/${BACKUP_FILE}"
kubectl -n lcm cp "${PG_POD}:/tmp/${BACKUP_FILE}" "./${BACKUP_FILE}"
ls -lh "./${BACKUP_FILE}"
```

备份文件需要进入站点既有备份系统；本 runbook 只覆盖生成和恢复命令。

---

## 4. PostgreSQL 恢复

恢复会覆盖当前库。执行前确认 Core 已停止，避免新写入。

compose：

```bash
docker-compose -f docker-compose.prod.yml stop lcm-core lcm-satellite lcm-frontend
docker cp ./lcm-backup.dump lcm-postgres:/tmp/lcm-backup.dump
docker exec lcm-postgres dropdb -U lcm_user --if-exists lcm_db
docker exec lcm-postgres createdb -U lcm_user lcm_db
docker exec lcm-postgres pg_restore -U lcm_user -d lcm_db /tmp/lcm-backup.dump
docker-compose -f docker-compose.prod.yml up -d
```

Helm：

```bash
kubectl -n lcm scale deploy/lcm-core --replicas=0
kubectl -n lcm cp ./lcm-backup.dump "${PG_POD}:/tmp/lcm-backup.dump"
kubectl -n lcm exec "$PG_POD" -- dropdb -U lcm --if-exists lcm
kubectl -n lcm exec "$PG_POD" -- createdb -U lcm lcm
kubectl -n lcm exec "$PG_POD" -- pg_restore -U lcm -d lcm /tmp/lcm-backup.dump
kubectl -n lcm scale deploy/lcm-core --replicas=2
kubectl -n lcm rollout status deploy/lcm-core
```

恢复后执行：

```bash
curl -sf http://localhost:8080/health/ready
```

并检查设备池、Job 历史、审计日志是否符合备份时间点。

---

## 5. mTLS 证书轮换

### 5.1 compose

1. 准备新的 `certs/` 文件，保持文件名不变。
2. 确认 `GRPC_TRUSTSTORE_PASSWORD` 与新 `truststore.jks` 一致。
3. 重启 Core，再重启 Satellite。

```bash
docker-compose -f docker-compose.prod.yml restart lcm-core
docker-compose -f docker-compose.prod.yml restart lcm-satellite
docker logs --tail 100 lcm-satellite
```

预期 Satellite 重新注册或恢复 heartbeat。

### 5.2 Helm

新建或替换 Secret：

```bash
kubectl -n lcm create secret generic lcm-tls \
  --from-file=ca.pem=certs/ca.pem \
  --from-file=server.pem=certs/server.pem \
  --from-file=server-pkcs8.key=certs/server-pkcs8.key \
  --from-file=client.pem=certs/client.pem \
  --from-file=client.key=certs/client.key \
  --from-file=truststore.jks=certs/truststore.jks \
  --from-literal=truststore-password=changeit \
  --dry-run=client -o yaml | kubectl apply -f -
```

重启工作负载：

```bash
kubectl -n lcm rollout restart deploy/lcm-core
kubectl -n lcm rollout restart daemonset/lcm-satellite
kubectl -n lcm rollout status deploy/lcm-core
kubectl -n lcm rollout status daemonset/lcm-satellite
```

---

## 6. JWT key 轮换

当前默认镜像会把 `core/src/main/resources/META-INF/resources/privateKey.pem` 与 `publicKey.pem` 打入 classpath。要在不重建镜像的情况下轮换 JWT key，需要把新 key 外置到 Secret 并设置：

```bash
JWT_PUBLIC_KEY_LOCATION=/app/certs/publicKey.pem
JWT_PRIVATE_KEY_LOCATION=/app/certs/privateKey.pem
```

compose 可将 key 放入 `certs/` 后重启 Core。Helm 可把 `publicKey.pem` / `privateKey.pem` 加入 `lcm-tls` Secret，并在 `core.env` 中追加：

```yaml
core:
  env:
    JWT_PUBLIC_KEY_LOCATION: /app/certs/publicKey.pem
    JWT_PRIVATE_KEY_LOCATION: /app/certs/privateKey.pem
```

轮换影响：

- 新签发 token 使用新私钥。
- 旧 token 可能在 public key 切换后失效；建议安排维护窗口并要求重新登录。
- 先在非生产 namespace 验证登录、刷新 token、API 鉴权，再进入生产。

---

## 7. 升级后验收

- [ ] Core `/health/ready` 返回成功。
- [ ] Satellite 恢复在线。
- [ ] 前端可登录。
- [ ] 设备池、Job 历史、审计日志存在。
- [ ] 新 Job 能提交并收到回调。
- [ ] AlertManager UI 可见。
- [ ] 备份文件已归档。

