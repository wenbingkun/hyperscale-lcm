# Alertmanager 运维手册 (Runbook)

> **Last Updated:** 2026-04-15
> **Scope:** Hyperscale LCM 的 Alertmanager 部署、Secret 注入、冒烟测试与常见故障排查。

---

## 1. 架构与数据流

```
┌────────────┐   scrape    ┌─────────────┐   push alerts   ┌───────────────┐
│ Quarkus    │◄────────────│ Prometheus  │───────────────►│ Alertmanager  │
│ Core :8080 │  /q/metrics │    :9090    │   :9093        │   v0.28.1     │
└────────────┘             └─────────────┘                 └───────┬───────┘
                            rule_files:                            │
                            prometheus-alerts.yml                  │ route by severity
                                                          ┌───────┴───────┐
                                                          │               │
                                                  critical-receiver  warning-receiver
                                                          │               │
                                              ┌───────┬───┘           ┌───┘
                                              ▼       ▼               ▼
                                          PagerDuty  Slack          Slack
                                                      Email
```

### 关键端口与配置文件

| 组件 | 端口 | K8s Service 名称 | 配置源 |
|------|------|----------------|--------|
| Alertmanager | 9093 | `{release}-alertmanager` | ConfigMap `{release}-alertmanager` (from `alertmanager.yml.tpl`) |
| Secret 挂载 | — | `{release}-alertmanager-secrets` | `/etc/alertmanager/secrets/` |

---

## 2. Prod 部署：Secret 注入两种方式

### 2.1 Chart-managed 模式（默认）

Helm chart 自动创建一个空 Secret `{release}-alertmanager-secrets`，部署后需手动填充真实凭据。

```bash
# 1. 启用 Slack receiver 并部署
helm upgrade my-lcm helm/hyperscale-lcm \
  --set monitoring.alertmanager.receivers.slack.enabled=true \
  --set monitoring.alertmanager.receivers.slack.channel="#prod-alerts"

# 2. 手动填充 Secret
kubectl edit secret my-lcm-alertmanager-secrets
# 将 slack-webhook-url 改为 base64 编码的真实 webhook URL
# echo -n 'https://hooks.slack.com/services/T.../B.../xxx' | base64
```

也可以在 `helm upgrade` 时一步到位：
```bash
kubectl create secret generic my-lcm-alertmanager-secrets \
  --from-literal=smtp-password='real-password' \
  --from-literal=slack-webhook-url='https://hooks.slack.com/services/T.../B.../xxx' \
  --from-literal=pagerduty-routing-key='your-integration-key' \
  --dry-run=client -o yaml | kubectl apply -f -
```

### 2.2 External 模式

适用于已有外部 Secret 管理系统（如 External Secrets Operator / Vault）的场景。

```bash
# 1. 预先创建 Secret（通过外部系统或手动）
kubectl create secret generic my-lcm-am-secrets \
  --from-literal=smtp-password='...' \
  --from-literal=slack-webhook-url='...' \
  --from-literal=pagerduty-routing-key='...'

# 2. 告诉 Helm 使用外部 Secret
helm upgrade my-lcm helm/hyperscale-lcm \
  --set monitoring.alertmanager.secrets.mode=external \
  --set monitoring.alertmanager.secrets.externalSecretName=my-lcm-am-secrets \
  --set monitoring.alertmanager.receivers.slack.enabled=true
```

---

## 3. Secret Key 格式规范

| Key | 格式 | 示例 |
|-----|------|------|
| `smtp-password` | 明文密码 | `my-smtp-p@ssword` |
| `slack-webhook-url` | 完整 Incoming Webhook URL | `https://hooks.slack.com/services/T00000000/B00000000/XXXXXXXXXXXXXXXX` |
| `pagerduty-routing-key` | PagerDuty **Integration Key**（非 Service Key） | `a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4` |

> **⚠️ 常见误区：** PagerDuty 有 Integration Key 和 Service Key 两种，Alertmanager 需要的是 Integration Key（32 字符 hex）。在 PagerDuty 控制台 → Service → Integrations → Events API v2 → Integration Key 处获取。

---

## 4. 验证：部署后冒烟测试

```bash
# 1. Port-forward Alertmanager
kubectl port-forward svc/my-lcm-alertmanager 9093:9093 &

# 2. 检查健康状态
curl -s http://localhost:9093/-/healthy
# 期望: OK

# 3. 推送一条测试 critical alert
curl -XPOST http://localhost:9093/api/v2/alerts \
  -H 'Content-Type: application/json' \
  -d '[{
    "labels": {
      "alertname": "TestCriticalAlert",
      "severity": "critical",
      "job": "smoke-test"
    },
    "annotations": {
      "summary": "This is a smoke test alert from runbook verification"
    }
  }]'

# 4. 验证 alert 已被接收
curl -s http://localhost:9093/api/v2/alerts | jq '.[].labels.alertname'
# 期望: "TestCriticalAlert"

# 5. 在 Slack / PagerDuty / 邮箱中确认收到通知
```

### Dev 本地冒烟（docker-compose）

```bash
docker-compose up -d prometheus alertmanager
# 等待启动
curl -s http://localhost:9093/-/healthy
# 然后运行 scripts/verify_alertmanager.sh
```

---

## 5. 常见故障

### 5.1 所有 channel 都 disabled → 告警沉默

**症状**：Prometheus 推送了告警，但没有任何通知到达。

**检查**：
```bash
# Alert 是否到达 Alertmanager
curl -s http://localhost:9093/api/v2/alerts | jq length
# 如果 > 0 说明到达了，问题在 receiver 配置

# 检查当前配置
curl -s http://localhost:9093/api/v2/status | jq '.config.original'
# 查看 critical-receiver / warning-receiver 下是否有 *_configs 块
```

**解决**：在 Helm values 中启用至少一个 receiver。

### 5.2 Slack `channel_not_found`

**原因**：Incoming Webhook 对应的 Slack App 没有被邀请进目标 channel。

**解决**：在 Slack 中 `/invite @your-app-name` 到对应 channel。

### 5.3 PagerDuty `invalid_routing_key`

**原因**：混淆了 Integration Key 和 Service Key。

**解决**：使用 PagerDuty → Service → Integrations → Events API v2 的 Integration Key（32 字符 hex）。

### 5.4 SMTP TLS 握手失败

**原因**：`require_tls: true` 但出口网络或 SMTP 服务器不支持 STARTTLS。

**解决**：
- 确认 SMTP 服务器支持 STARTTLS
- 如果使用内部中继且已有其他加密层，可设置 `monitoring.alertmanager.receivers.email.requireTls=false`

### 5.5 Chart-managed Secret 未填充

**症状**：部署成功但 `_file` 引用指向空文件，alertmanager 日志报 `empty file` 或类似错误。

**解决**：按第 2.1 节填充真实 Secret 值。

---

## 6. 静音 (Silence)

```bash
# Port-forward Alertmanager
kubectl port-forward svc/my-lcm-alertmanager 9093:9093 &

# 创建 2 小时静音
amtool silence add \
  --alertmanager.url=http://localhost:9093 \
  --duration=2h \
  --comment="Planned maintenance window" \
  alertname="HighCpuUsage"

# 查看活跃静音
amtool silence query --alertmanager.url=http://localhost:9093

# 移除静音
amtool silence expire --alertmanager.url=http://localhost:9093 <silence-id>
```

---

## 7. Helm Values 速查

| 路径 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `monitoring.alertmanager.enabled` | bool | `true` | 总开关 |
| `monitoring.alertmanager.secrets.mode` | string | `chart-managed` | `chart-managed` \| `external` |
| `monitoring.alertmanager.secrets.externalSecretName` | string | `""` | 仅 `external` 模式 |
| `monitoring.alertmanager.receivers.email.enabled` | bool | `false` | |
| `monitoring.alertmanager.receivers.email.to` | string | `""` | |
| `monitoring.alertmanager.receivers.email.from` | string | `""` | |
| `monitoring.alertmanager.receivers.email.smarthost` | string | `""` | 如 `smtp.example.com:587` |
| `monitoring.alertmanager.receivers.email.requireTls` | bool | `true` | |
| `monitoring.alertmanager.receivers.slack.enabled` | bool | `false` | |
| `monitoring.alertmanager.receivers.slack.channel` | string | `#alerts` | |
| `monitoring.alertmanager.receivers.pagerduty.enabled` | bool | `false` | |
| `monitoring.alertmanager.route.groupWait` | string | `10s` | |
| `monitoring.alertmanager.route.groupInterval` | string | `5m` | |
| `monitoring.alertmanager.route.repeatInterval` | string | `3h` | |

---

## 8. 规则源分叉说明

当前告警规则存在两套源：
- **Helm chart**：`helm/hyperscale-lcm/templates/monitoring.yaml` 中的 `PrometheusRule` CRD
- **Docker-compose dev**：`infra/prometheus-alerts.yml` 静态规则文件

两者的告警定义基本一致但独立维护。合并为单一来源是 follow-up 项，详见 [PROJECT_STATUS.md](../PROJECT_STATUS.md)。

**影响**：修改告警规则时需同步两处。新增规则如果只加到一处，另一个环境不会生效。
