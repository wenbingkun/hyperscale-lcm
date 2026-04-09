# Hyperscale LCM Demo Guide

`./scripts/demo.sh run` 会在本机拉起一套可重复验证的 Demo 闭环：

1. 启动 `docker-compose` 基础设施（PostgreSQL / Redis / Kafka / Jaeger）
2. 启动 Quarkus Core
3. 启动真实 Satellite，并让它在本地 demo 模式下通过 gRPC 注册到 Core
4. 启动本地 mock Redfish HTTPS 服务与 mock SSH 服务
5. 用 `curl` 创建凭据档案
6. 用 `grpcurl` 注入 Redfish discovery 事件
7. 用 `curl` 执行审批与 claim，验证托管账号已写入 mock Redfish
8. 用 `curl` 提交 SSH Job，等待 Satellite 完成调度与回调
9. 用 `websocat` 订阅 `/ws/dashboard`，验证发现、心跳和作业状态推送

## Prerequisites

需要以下命令可用：

- `curl`
- `docker`
- `docker-compose`
- `jq`
- `grpcurl`
- `websocat`
- `go`
- `java`
- `python3`
- `nc`
- `ssh`
- `ssh-keygen`
- `stdbuf`

首次运行前如果仓库里还没有 JWT / mTLS 证书，脚本会自动执行 [generate_keys.sh](/home/wenbk/projects/work/hyperscale-lcm/scripts/generate_keys.sh)。
本地 demo 为了降低环境门槛，会让 Satellite 以 `LCM_GRPC_PLAINTEXT=true` 连接本机 `quarkusDev` Core；生产路径仍然使用仓库默认的 mTLS 配置。

## Run

```bash
./scripts/demo.sh run
```

脚本成功后会输出一段 JSON 摘要，包含：

- `satelliteId`
- 发现到的 mock BMC 设备状态
- SSH Job 最终状态
- WebSocket 日志路径

默认运行时目录是 `/tmp/hyperscale-lcm-demo`，相关日志包括：

- `/tmp/hyperscale-lcm-demo/core.log`
- `/tmp/hyperscale-lcm-demo/satellite.log`
- `/tmp/hyperscale-lcm-demo/mock-redfish.log`
- `/tmp/hyperscale-lcm-demo/mock-ssh.log`
- `/tmp/hyperscale-lcm-demo/dashboard-ws.log`

默认会为每次运行生成新的 `demo-lab-<timestamp>` 集群名，避免复用旧的 Satellite / discovery 数据；如需固定集群名，可显式设置 `LCM_DEMO_CLUSTER`。
mock SSH 服务默认监听 `127.0.0.1:22222`，也可通过 `LCM_DEMO_SSH_PORT` 覆盖。
mock Redfish 默认使用 `openbmc-baseline` fixture；如需切到其他厂商 smoke，可设置 `LCM_DEMO_REDFISH_PROFILE=dell-idrac|hpe-ilo|lenovo-xcc`。

## What The Demo Proves

- Core 与 Satellite 的本地 gRPC 注册链路可用
- `grpcurl` 注入的 discovery 事件会进入发现池并触发 BMC claim 规划
- Redfish claim 与托管账号下发可在共享 vendor fixture 驱动的本地 mock 环境完成
- Job 调度、Satellite SSH 执行和状态回调闭环可用
- Dashboard WebSocket 会收到 `DISCOVERY_EVENT`、`HEARTBEAT_UPDATE`、`JOB_STATUS`

## Cleanup

```bash
./scripts/demo.sh cleanup
```

该命令会停止脚本启动的本地进程，并执行 `docker-compose down`。
