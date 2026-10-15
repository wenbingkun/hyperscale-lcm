# 单机实验环境快速上手（无真机）

目标：在一台 Linux 主机上，从干净数据跑通主流程——登录 → 批准设备 → 对已批准设备执行 SSH 只读任务 → 在页面看到真实输出，并能在库里查到审计。**仅用于本机实验**：启用了硬编码开发账号，`docker-compose.lab.yml` 不是生产配置（生产登录/权限映射是 M4 门槛，见 [NO_HARDWARE_PHASE_PLAN.md](../NO_HARDWARE_PHASE_PLAN.md)）。

## 前置

- Docker Engine + Compose v2 插件（`docker compose`），`jq`、`curl`、`openssl`、`ssh-keygen`。
- 仓库根目录执行；未发布的 `v0.1.1` 需先本地构建（见 [deployment.md §3.1](deployment.md)）。
- 主机端口 5432/6379/9092/3000/9090/9093/16686/4317/5173/8080/8443 空闲。`docker-compose.prod.yml` 把依赖服务发布在宿主端口，并写死了容器名；若本机有其他 LCM 开发环境（例如自己的 Redis 占着 6379），先停掉它们。
- **必须**用独立项目名 `-p lcm-lab`。不加 `-p` 时 compose 会按目录名把同目录下已有的开发容器（同名服务）当作自己的并重建它们。

## 步骤

```bash
export DOCKER_NAMESPACE=local DOCKER_TAG=v0.1.1
export DB_PASSWORD='<任意>' GRAFANA_PASSWORD='<任意>' GRPC_TRUSTSTORE_PASSWORD=changeit

DOCKER_NAMESPACE=local scripts/build.sh v0.1.1      # 本地构建三个应用镜像
scripts/lab_prepare.sh                              # 证书、实验 SSH 密钥、sshd 主机密钥、严格 known_hosts → lab/data（已 gitignore）
docker compose -p lcm-lab -f docker-compose.prod.yml -f docker-compose.lab.yml up -d --build
curl -sf http://127.0.0.1:8080/health/ready
scripts/lab_bootstrap.sh                            # 经 REST 登记并批准 Satellite 自身地址；SSH 目标留为 PENDING
```

`lab_bootstrap.sh` 之后约一分钟内 Satellite 完成注册（`docker logs lcm-satellite` 出现 `Heartbeat sent`）。固定地址：Satellite `172.30.77.20`，SSH 目标 `172.30.77.30`。

然后用浏览器打开 <http://127.0.0.1:5173>，账号 `admin` / `admin123`，租户 `default`：

1. Discovery 页批准 `172.30.77.30`（或 `scripts/lab_bootstrap.sh --approve-target` 预先批准）。
2. Dashboard 的 Job Type 选 **SSH Maintenance**，选目标设备，Launch。
3. Jobs → 打开作业，状态 Completed，**SSH Task Result** 显示真实 `uname/uptime/df/free` 输出。
4. 审计：`docker exec lcm-postgres psql -U lcm_user -d lcm_db -c "select event_type, actor from audit_log order by 1"` 应有 `JOB_SUBMITTED`（操作人为 admin）、`JOB_SCHEDULED`、`JOB_COMPLETED`。

自动化同一流程（连真实后端，不用 API mock）：

```bash
cd frontend && npx playwright install chromium   # 首次
LCM_REAL_BASE_URL=http://127.0.0.1:5173 LCM_REAL_TARGET_IP=172.30.77.30 \
  npx playwright test -c playwright.real.config.ts
```

## 这个环境刻意做了什么

- 登录：Core 以 `LCM_AUTH_DEV_USERS_ENABLED=true` 启用开发账号并开放 `/api/auth/*`；8080 只绑回环地址。
- Satellite 使用 `lab/data/satellite-certs` 里**自己的**客户端证书（CN=`satellite-lab`），Core 按证书指纹识别它（[deployment.md §2.3](deployment.md)）。
- 凭据不进 Core：SSH 私钥只挂载到 Satellite（只读），主机密钥用严格 known_hosts 校验。
- Satellite 默认不启动 PXE 与网络发现，不会影响宿主网络。

## 清理

```bash
docker compose -p lcm-lab -f docker-compose.prod.yml -f docker-compose.lab.yml down -v
rm -rf lab/data
```

## 已知边界

- 这是软件实验验收，不代表真机、BMC 或生产安全验收；Deployment Closure Step 5 的原验收条件不因本环境而关闭。
- Satellite 与目标设备的可达关系（哪个 Satellite 能 SSH 到哪台设备）未建模；任务取消、重试、结果未知等语义属于 M2。
