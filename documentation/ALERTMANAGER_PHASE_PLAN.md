# Phase AlertManager：外部通知打通（P0 收口）

> Updated: 2026-04-15
> 由 Claude code 编写。

## Summary

- Phase AlertManager 专注于把当前仓库中**已存在但未激活**的告警链路彻底打通，是 [PROJECT_STATUS.md](PROJECT_STATUS.md) 中剩下的唯一纯软件 P0 阻塞项的收口工作。
- 当前状态梳理（2026-04-15）：
  1. Helm chart 已带 `prom/alertmanager:v0.28.1` Deployment + Service + ConfigMap（见 [helm/hyperscale-lcm/templates/alertmanager.yaml](../helm/hyperscale-lcm/templates/alertmanager.yaml)），默认 `monitoring.alertmanager.enabled=true`。
  2. 生产 Prometheus 已正确指向 Alertmanager（[infra/prometheus-prod.yml](../infra/prometheus-prod.yml) 第 8-12 行含 `alerting.alertmanagers [alertmanager:9093]`），[docker-compose.prod.yml](../docker-compose.prod.yml) 也已 wire 好服务依赖。
  3. **但** 三条 receiver（email / slack / pagerduty）仍是 `CHANGE_ME` / `REPLACE_ME` 占位，见 [helm/hyperscale-lcm/files/alertmanager.yml](../helm/hyperscale-lcm/files/alertmanager.yml) 与 [infra/alertmanager/alertmanager.yml](../infra/alertmanager/alertmanager.yml)。哪怕 Prometheus 真的推送告警过来，alertmanager 也会沉默丢弃，运维收不到任何通知。
  4. Helm chart 目前用 `.Files.Get "files/alertmanager.yml" | nindent 4` **静态引用**配置文件（[templates/alertmanager.yaml 第 11 行](../helm/hyperscale-lcm/templates/alertmanager.yaml#L11)），`values.yaml` 无法插值，导致"按值开关 receiver"做不了。
  5. Dev 侧 [infra/prometheus.yml](../infra/prometheus.yml) **没有** `alerting:` 段，[docker-compose.yml](../docker-compose.yml) 也没有 alertmanager 服务——本地开发走不通告警链路，无法做 routing 回归。
  6. [.github/workflows/ci.yml](../.github/workflows/ci.yml) 目前**没有任何** helm chart 校验（lint / template / amtool check-config），坏 chart 配置可以不受阻拦地合入 main。
- Phase 定位：**一次"把存在但未激活的告警链路激活"的收口工作**，不是 observability 大改。
- 明确**不做**：迁移到 kube-prometheus-stack operator；改写或合并 [helm/hyperscale-lcm/templates/monitoring.yaml](../helm/hyperscale-lcm/templates/monitoring.yaml) 中的 `PrometheusRule` CRD 与 [infra/prometheus-alerts.yml](../infra/prometheus-alerts.yml) 两套规则源（已知分叉，留作 follow-up）；引入 External Secrets Operator / HashiCorp Vault / SOPS / Grafana OnCall / Incident.io；调整现有告警的严重度或阈值；触碰 Core / Satellite / Frontend 任何代码；冻结 `lcm.proto` 与 gRPC 生成物。

## Key Changes

以下按执行顺序组织。每步完成后必须在本地跑对应验证 → 自审 4 问 → commit；禁用 `git commit --no-verify` 与 `git push --force`。

### Step 0 — 基线确认（纯文档）

**目标**：本份 `ALERTMANAGER_PHASE_PLAN.md` 固化现状 + Phase 边界，让所有后续 step 不得再动"规则源分叉"、"v0.28.1 镜像版本"、"Core/Satellite/Frontend 代码不碰"等已知边界条件。

**自审 4 问**：
- 文档是否把 Phase 边界写死（避免 scope creep）？
- 是否列出所有现有文件的锚点以便后续 PR 审查参照？
- 是否标记好 "follow-up"（规则源合并、kube-prometheus-stack 迁移）？
- commit 后 CI 是否仍然绿（纯文档改动应无风险）？

**commit**：`docs: add Phase AlertManager productionization plan`

### Step 1 — K8s Secret 模板 + values.yaml 接入

**新增**：`helm/hyperscale-lcm/templates/alertmanager-secret.yaml`
- 按 `{{- if and .Values.monitoring.alertmanager.enabled (eq .Values.monitoring.alertmanager.secrets.mode "chart-managed") }}` 条件渲染
- `kind: Secret`, `stringData:` 三个键：`smtp-password`、`slack-webhook-url`、`pagerduty-routing-key`
- **默认为空字符串**；install 后靠 `kubectl edit secret ...` 或 Helm `--set-string` 覆写
- 当 `secrets.mode=external` 时完全不渲染此 Secret，改用 `externalSecretName` 引用预先创建好的 Secret（对接外部 secret 管理系统）

**修改**：[helm/hyperscale-lcm/values.yaml](../helm/hyperscale-lcm/values.yaml) 第 187 行起的 `alertmanager:` 段追加：

```yaml
monitoring:
  alertmanager:
    # ...现有字段保留...
    secrets:
      mode: chart-managed     # chart-managed | external
      externalSecretName: ""  # 仅 mode=external 时生效
    receivers:
      email:
        enabled: false
        to: ""
        from: ""
        smarthost: ""
        requireTls: true
      slack:
        enabled: false
        channel: "#alerts"
      pagerduty:
        enabled: false
    route:
      groupWait: 10s
      groupInterval: 5m
      repeatInterval: 3h
```

**自审 4 问**：
- 默认 `enabled: false` 是否保证"升级现有 chart 不会开始发空 webhook"？
- `mode: external` 路径是否真正绕过 chart-managed Secret 渲染？
- 字段命名风格是否与现有 `values.yaml` 一致（camelCase），同时与 alertmanager 官方字段（`require_tls`）之间的转换在 `files/alertmanager.yml.tpl` 里做？
- `helm template hyperscale-lcm helm/hyperscale-lcm` 默认值渲染是否通过？

**commit**：`feat(helm): externalize alertmanager receiver secrets via values + k8s secret`

### Step 2 — 切换 `templates/alertmanager.yaml` 为 `tpl` 渲染 + 挂载 Secret

**修改**：[helm/hyperscale-lcm/templates/alertmanager.yaml 第 11 行](../helm/hyperscale-lcm/templates/alertmanager.yaml#L11)

将 `{{- .Files.Get "files/alertmanager.yml" | nindent 4 }}` 改为：

```yaml
data:
  alertmanager.yml: |
{{ tpl (.Files.Get "files/alertmanager.yml.tpl") . | indent 4 }}
```

同时在 Deployment 段追加 Secret 挂载（`volumeMounts` + `volumes`）：

```yaml
volumeMounts:
  - name: secrets
    mountPath: /etc/alertmanager/secrets
    readOnly: true
# ...
volumes:
  - name: secrets
    secret:
      secretName: {{ if eq .Values.monitoring.alertmanager.secrets.mode "external" }}{{ .Values.monitoring.alertmanager.secrets.externalSecretName }}{{ else }}{{ .Release.Name }}-alertmanager-secrets{{ end }}
      optional: true
```

**自审 4 问**：
- `tpl` 是否真的能拿到 `.Values.monitoring.alertmanager.receivers.*`？（是，`tpl` 接 root context）
- `optional: true` 是否允许 `externalSecretName` 不存在时不崩溃？（允许）
- `secrets` volume 在 `receivers.{*}.enabled=false` 时是否会挂空目录？（会，无害）
- 原有静态 `files/alertmanager.yml` 是否要删除？（**要**：将被 `.tpl` 取代，避免残留双份）

**commit**：`refactor(helm): render alertmanager config via tpl to enable value-driven receivers`

### Step 3 — 重写 `files/alertmanager.yml.tpl`：模板化 + `_file` 引用

**新文件**：`helm/hyperscale-lcm/files/alertmanager.yml.tpl`（替换原 `alertmanager.yml`）

核心结构要点：
- 所有 secret 字段走 `_file` 从挂载路径读取，**不把 secret 明文写进 ConfigMap**
- 按 `.Values.monitoring.alertmanager.receivers.{email,slack,pagerduty}.enabled` 条件渲染 receiver 子块
- Route tree 按 `severity` 分流到 `critical-receiver` / `warning-receiver`，`default-receiver` 保持空作兜底

```yaml
global:
  resolve_timeout: 5m

route:
  receiver: default-receiver
  group_by: [alertname, job, severity]
  group_wait: {{ .Values.monitoring.alertmanager.route.groupWait }}
  group_interval: {{ .Values.monitoring.alertmanager.route.groupInterval }}
  repeat_interval: {{ .Values.monitoring.alertmanager.route.repeatInterval }}
  routes:
    - matchers:
        - severity="critical"
      receiver: critical-receiver
      continue: false
    - matchers:
        - severity="warning"
      receiver: warning-receiver
      continue: false

receivers:
  - name: default-receiver

  - name: critical-receiver
    {{- if .Values.monitoring.alertmanager.receivers.pagerduty.enabled }}
    pagerduty_configs:
      - routing_key_file: /etc/alertmanager/secrets/pagerduty-routing-key
        severity: '{{ `{{ .CommonLabels.severity | default "critical" }}` }}'
    {{- end }}
    {{- if .Values.monitoring.alertmanager.receivers.slack.enabled }}
    slack_configs:
      - api_url_file: /etc/alertmanager/secrets/slack-webhook-url
        channel: {{ .Values.monitoring.alertmanager.receivers.slack.channel | quote }}
        send_resolved: true
        title: '{{ `{{ .CommonLabels.alertname }}` }}'
        text: '{{ `{{ range .Alerts }}{{ .Annotations.summary }}{{ end }}` }}'
    {{- end }}
    {{- if .Values.monitoring.alertmanager.receivers.email.enabled }}
    email_configs:
      - to: {{ .Values.monitoring.alertmanager.receivers.email.to | quote }}
        from: {{ .Values.monitoring.alertmanager.receivers.email.from | quote }}
        smarthost: {{ .Values.monitoring.alertmanager.receivers.email.smarthost | quote }}
        auth_username: {{ .Values.monitoring.alertmanager.receivers.email.from | quote }}
        auth_password_file: /etc/alertmanager/secrets/smtp-password
        require_tls: {{ .Values.monitoring.alertmanager.receivers.email.requireTls }}
    {{- end }}

  - name: warning-receiver
    {{- if .Values.monitoring.alertmanager.receivers.slack.enabled }}
    slack_configs:
      - api_url_file: /etc/alertmanager/secrets/slack-webhook-url
        channel: {{ .Values.monitoring.alertmanager.receivers.slack.channel | quote }}
        send_resolved: true
        title: '{{ `[WARN] {{ .CommonLabels.alertname }}` }}'
        text: '{{ `{{ range .Alerts }}{{ .Annotations.summary }}{{ end }}` }}'
    {{- end }}
```

**双重 escape 陷阱**：alertmanager 模板语法与 Helm 模板语法都用 `{{ }}`，必须用反引号字符串（```{{ ` ... ` }}```）让 Helm 原样透传 alertmanager 模板到最终 ConfigMap。执行者实现时务必对 `title` / `text` / `severity` 字段做一次 `amtool check-config` 验证转义是否正确。

**删除**：[helm/hyperscale-lcm/files/alertmanager.yml](../helm/hyperscale-lcm/files/alertmanager.yml)（被 `.tpl` 取代）

**自审 4 问**：
- alertmanager v0.28.1 是否支持 `routing_key_file` / `api_url_file` / `auth_password_file`？（v0.26+ 支持，v0.28.1 OK；仍需执行阶段用 `amtool check-config` 实测确认）
- `tpl` + 反引号转义是否真的能正确输出 alertmanager `{{ .CommonLabels.alertname }}`？
- Helm 默认值渲染（全部 `enabled=false`）的 yaml 是否 `amtool check-config` 通过？
- 是否可能出现 `critical-receiver` 完全空白（所有子 block 都被 disabled）？alertmanager 是否接受空 receiver？（接受，会沉默丢弃）

**commit**：`feat(helm): parameterize alertmanager receivers with file-based secret refs`

### Step 4 — Dev 侧 docker-compose 告警链路补齐

**目标**：让 Step 6 的 `scripts/verify_alertmanager.sh` 能在本地 docker-compose 上跑完整 routing 验证，无需 k8s 集群。

**修改**：
- [infra/prometheus.yml](../infra/prometheus.yml) — 追加 `alerting:` 段指向 `alertmanager:9093`
- [docker-compose.yml](../docker-compose.yml) — 新增 `alertmanager` service，挂载 `./infra/alertmanager/alertmanager.yml`，`prometheus` 服务追加 `depends_on: [alertmanager]`
- [infra/alertmanager/alertmanager.yml](../infra/alertmanager/alertmanager.yml) — 同步 Step 3 的 route tree 结构（保留 dev 占位值，不需要真 secret；dev 场景下 `critical-receiver` / `warning-receiver` 全部留空块，`default-receiver` 保持空）

**自审 4 问**：
- `docker-compose up` 后 `curl localhost:9093/-/healthy` 是否返回 200？
- Prometheus UI 的 "Alerts" tab 是否能看到 rule 状态？
- `amtool alert add` 从 host → alertmanager 是否能成功？
- 现有 `docker-compose up` 用户（例如跑 `scripts/demo.sh`）是否因新增服务而阻塞？（alertmanager 启动 <5s，可接受）

**commit**：`feat(infra): wire dev prometheus to alertmanager for local routing verification`

### Step 5 — Runbook 文档

**新文件**：`documentation/runbooks/alertmanager.md`（目录若不存在则新建）

骨架：
1. **架构与数据流**：一张 ASCII 图说明 Prometheus → Alertmanager → Email/Slack/PagerDuty；关键端口与配置文件路径
2. **Prod 部署：secret 注入两种姿势**
   - `chart-managed`：`helm upgrade ... --set-string monitoring.alertmanager.receivers.slack.enabled=true` 然后 `kubectl edit secret {release}-alertmanager-secrets` 填 base64 值
   - `external`：预先 `kubectl create secret generic my-lcm-am-secrets ...`，然后 `--set monitoring.alertmanager.secrets.mode=external --set monitoring.alertmanager.secrets.externalSecretName=my-lcm-am-secrets`
3. **三个 key 的格式规范**：`smtp-password` 明文、`slack-webhook-url` 完整 URL（`https://hooks.slack.com/services/...`）、`pagerduty-routing-key` integration key（非 service key）
4. **验证：部署后冒烟测试**：`kubectl port-forward svc/{release}-alertmanager 9093` → `amtool alert add ... --severity=critical`，在 Slack/PagerDuty/邮箱里确认收到
5. **常见故障**：
   - 所有 channel 都 disabled → alert route 到空块，alertmanager 沉默；检查方法：`curl /api/v2/alerts`
   - Slack `channel_not_found` → 检查 webhook URL 对应的 Slack app 是否被踢出该 channel
   - PagerDuty `invalid_routing_key` → 区分 integration key vs service key
   - SMTP TLS 握手失败 → `require_tls: true` 与出口网络的兼容性
6. **静音**：`amtool silence add ...` + `kubectl port-forward` 示例
7. **规则源分叉说明**：当前 helm PrometheusRule 与 docker-compose `infra/prometheus-alerts.yml` 是两套，follow-up 项见 [PROJECT_STATUS.md](PROJECT_STATUS.md)

**自审 4 问**：
- 是否让一个不熟悉本项目的 SRE 看一眼就知道怎么接入真实 Slack？
- 是否把 "chart-managed 后必须手动填 secret" 的陷阱写进 "常见故障"？
- 是否引用了 Step 1/2/3 的 values 字段真实名称（避免和 plan 漂移）？
- `documentation/runbooks/` 目录位置与仓库现有文档组织是否一致？（可能需新建目录）

**commit**：`docs: add alertmanager runbook covering secret injection and smoke testing`

### Step 6 — `scripts/verify_alertmanager.sh` 验证脚本

**新文件**：`scripts/verify_alertmanager.sh`

职责：
- 检查 `docker-compose ps` 中 `alertmanager` / `prometheus` 是否 Up
- 用 `curl -XPOST http://localhost:9093/api/v2/alerts` 推一条 fake `severity=critical` alert
- 轮询 `/api/v2/alerts`（最多 10 次 × 1s）直到出现该 alert
- 用 `/api/v2/status` 校验 alertmanager cluster status
- **不**测试真实 SMTP/Slack/PagerDuty（dev 占位无 secret）
- 返回码：0 通过 / 1 失败 / 2 前置条件未满足
- 依赖：`curl` + `jq`（若缺则优雅退出）
- 兼容：同时支持 `docker-compose`（v1）与 `docker compose`（v2）

脚本风格对齐 [scripts/check_ci_contract.sh](../scripts/check_ci_contract.sh) / [scripts/demo.sh](../scripts/demo.sh)（bash `set -euo pipefail`，彩色输出）。

**自审 4 问**：
- 本地 `docker-compose up` 后直接跑能一次通过？
- 是否对 `jq` 缺失做了 graceful degrade？
- 是否会因 `docker-compose` 版本（v1 vs v2 `docker compose`）差异崩溃？
- 是否能被 CI 复用？（明确不：CI 走 amtool check-config；本脚本只做 dev 手动冒烟）

**commit**：`feat(scripts): add verify_alertmanager.sh for dev routing smoke`

### Step 7 — CI helm chart lint + amtool check-config job

**修改**：[.github/workflows/ci.yml](../.github/workflows/ci.yml) — 新增 `helm-chart-lint` job，并行于 `backend-tests`：

```yaml
helm-chart-lint:
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - uses: azure/setup-helm@v4
      with:
        version: 'v3.14.0'
    - name: helm lint
      run: helm lint helm/hyperscale-lcm
    - name: render default values
      run: helm template hyperscale-lcm helm/hyperscale-lcm > /tmp/rendered.yaml
    - name: extract alertmanager ConfigMap
      run: |
        # 用 python/yq 把 data."alertmanager.yml" 抠出来写到 /tmp/am.yml
    - name: install amtool
      run: |
        curl -L https://github.com/prometheus/alertmanager/releases/download/v0.28.1/alertmanager-0.28.1.linux-amd64.tar.gz | tar xz
        sudo mv alertmanager-0.28.1.linux-amd64/amtool /usr/local/bin/
    - name: amtool check-config
      run: amtool check-config /tmp/am.yml
    - name: render with slack enabled matrix
      run: |
        helm template hyperscale-lcm helm/hyperscale-lcm \
          --set monitoring.alertmanager.receivers.slack.enabled=true > /tmp/rendered2.yaml
        # 再次抠 ConfigMap + amtool check-config
```

**自审 4 问**：
- 是否与 [documentation/CI_CONTRACT.md](CI_CONTRACT.md) 的现有硬规则冲突？（实现前要先读一遍 CI_CONTRACT 确认）
- job 运行时间是否 <90s？（helm lint + template ~5s，amtool 安装 ~20s，check-config ~1s）
- 抠 ConfigMap 的 yaml 解析方式（yq / python）是否稳定？
- 如果 `check-config` 挂，整个 PR 是否能 block merge？（要配置 required check）

**commit**：`ci: add helm chart lint + amtool check-config guard`

### Step 8 — PROJECT_STATUS.md 状态刷新

**修改**：[documentation/PROJECT_STATUS.md](PROJECT_STATUS.md)
- 顶部 `Last Updated` 刷到当日
- 1.3 节 "AlertManager 外部通知" 行从 ❌ 改为 ✅（**但保留提示**："默认 disabled，需按 runbook 注入真实 secret 才生效"）
- 第 3 节 P0 行移除 AlertManager 条目（或降级到 P1 的 "真实 channel 接入与冒烟"）
- 追加链接到本份 `ALERTMANAGER_PHASE_PLAN.md`

**自审 4 问**：
- 表达是否诚实？（代码就绪 ≠ 真实 Slack 已接入）
- 是否保留了"仍需 prod secret 注入"的提示避免未来误读？
- 是否把 Phase AlertManager 链接到新 plan 文档？
- commit 是否只触碰 PROJECT_STATUS，避免串 scope？

**commit**：`docs: mark Phase AlertManager productionization landed`

### Step 9 — 完整回归 + PR 提交

在推送 PR 前本地依次跑：
1. `./scripts/check_ci_contract.sh`
2. `helm lint helm/hyperscale-lcm`
3. `helm template hyperscale-lcm helm/hyperscale-lcm | head -200`（肉眼 sanity check ConfigMap 段）
4. `docker-compose up -d prometheus alertmanager` + `scripts/verify_alertmanager.sh`
5. `cd core && ./gradlew check --no-daemon`（核心 Java 未变更，但回归一遍保底）
6. `cd satellite && go test ./... -count=1`
7. `cd frontend && npm test && npm run build`
8. 开 PR，等 CI 全绿（特别是新的 `helm-chart-lint` job）
9. Review → merge（**保留提交历史，不 squash**，与 Phase 8 的 PR #19 做法一致）

**commit** 的时机：每个 Step 完成且自审通过后**立即 commit 并推送到 feature 分支**，不攒堆。CI 会在每次 push 上跑，早暴露问题。

## Public Interfaces

- **Helm values 新增**（全部向后兼容，默认 `enabled=false`，升级现有部署无副作用）：
  - `monitoring.alertmanager.secrets.mode`（`chart-managed` | `external`）
  - `monitoring.alertmanager.secrets.externalSecretName`
  - `monitoring.alertmanager.receivers.{email,slack,pagerduty}.enabled` + 子字段
  - `monitoring.alertmanager.route.{groupWait,groupInterval,repeatInterval}`
- **Helm secret 名称**：`{{ .Release.Name }}-alertmanager-secrets`（chart-managed 模式）
- **Secret 键名**：`smtp-password`、`slack-webhook-url`、`pagerduty-routing-key`
- **Secret 挂载路径**：`/etc/alertmanager/secrets/`
- **Route tree 标签约定**：依赖 `severity="critical"` / `severity="warning"` label，与 [infra/prometheus-alerts.yml](../infra/prometheus-alerts.yml) 与 [helm/hyperscale-lcm/templates/monitoring.yaml](../helm/hyperscale-lcm/templates/monitoring.yaml) 现有规则一致，**不需要改 rule 本身**
- **显式不变**：Core / Satellite / Frontend 所有代码与配置、`lcm.proto`、JaCoCo 门禁、[k8s/*](../k8s/) 原始清单、[helm/hyperscale-lcm/templates/monitoring.yaml](../helm/hyperscale-lcm/templates/monitoring.yaml) 中 PrometheusRule 的 rule 定义

## Test Plan

| 层 | 命令 | 新增 / 存量 |
|---|---|---|
| Chart lint | `helm lint helm/hyperscale-lcm` | 新增 CI job |
| Chart 渲染 + 配置合法 | `helm template ... \| yq ... \| amtool check-config -` | 新增 CI job |
| Chart 渲染矩阵 | `--set monitoring.alertmanager.receivers.slack.enabled=true` 再次 check-config | 新增 CI job |
| Dev routing 冒烟 | `scripts/verify_alertmanager.sh` | 新增，本地手动 |
| Core | `cd core && ./gradlew check --no-daemon` | 存量回归 |
| Satellite | `cd satellite && go test ./... -count=1` | 存量回归 |
| Frontend | `cd frontend && npm test && npm run build` | 存量回归 |

**真实 channel 验证不在 Phase 交付范围** — 由 runbook 指导运维在部署后手动跑 `amtool alert add` 确认 Slack / PagerDuty / 邮件真到。原因：CI 不应该持有生产 secret；真实通道验证需要生产环境凭据。

## Implementation Notes（占位，留给执行者回填）

- **`tpl` 双重 escape 的具体坑**：执行者在 Step 3 落地后，记录反引号转义的最终命中方案与 `amtool check-config` 报错样本
- **`amtool` 提取 ConfigMap 的 yq 命令**：Step 7 确定最终用 `yq eval` 还是 Python 脚本，写清楚
- **docker-compose v1/v2 兼容处理**：Step 6 脚本如何探测两种 CLI 并 fallback
- **Runbook 目录位置决定**：`documentation/runbooks/` 新建 vs 平铺到 `documentation/` 根（由执行者看仓库现有组织决定）
- **`amtool check-config` 对 `_file` 引用的行为**：若要求文件真存在，Step 7 CI 需先 `touch /etc/alertmanager/secrets/{smtp-password,slack-webhook-url,pagerduty-routing-key}` 再 check

## Assumptions

- Prometheus 现有规则的 `severity` label 覆盖 `critical` / `warning` 两档，这与当前 8 条规则一致（[infra/prometheus-alerts.yml](../infra/prometheus-alerts.yml)）
- 生产运维有能力在 `helm upgrade` 之后手动注入 Secret 或预先创建 Secret — 本 Phase 不提供 External Secrets Operator / Vault / SOPS 集成
- `amtool` v0.28.1 `check-config` 接受 `_file` 引用而不需文件真存在（要在 Step 7 验证；若不接受，改用 CI 预先写空文件到期望路径再 check）
- Phase AlertManager 不承担"告警规则调优"责任 — 即使发现当前某条规则过于敏感或太迟钝，也不在此 PR 内改
- Helm v3.14.0 满足 `tpl (.Files.Get ...)` 能力需求（经验证该能力在 v3.0+ 即已稳定）

## 关键文件清单（Phase 执行阶段要触碰的文件）

**新增**：
- [documentation/ALERTMANAGER_PHASE_PLAN.md](ALERTMANAGER_PHASE_PLAN.md)（本文件，Step 0 产出）
- `documentation/runbooks/alertmanager.md`（Step 5，目录可能需新建）
- `helm/hyperscale-lcm/templates/alertmanager-secret.yaml`（Step 1）
- `helm/hyperscale-lcm/files/alertmanager.yml.tpl`（Step 3）
- `scripts/verify_alertmanager.sh`（Step 6）

**修改**：
- [helm/hyperscale-lcm/templates/alertmanager.yaml](../helm/hyperscale-lcm/templates/alertmanager.yaml) — 切 `tpl` + Secret 挂载（Step 2）
- [helm/hyperscale-lcm/values.yaml](../helm/hyperscale-lcm/values.yaml) — 追加 receivers/secrets/route 配置（Step 1）
- [infra/prometheus.yml](../infra/prometheus.yml) — 追加 `alerting:` 段（Step 4）
- [infra/alertmanager/alertmanager.yml](../infra/alertmanager/alertmanager.yml) — 同步 Step 3 的 route tree 结构（dev 占位，Step 4）
- [docker-compose.yml](../docker-compose.yml) — 新增 alertmanager service + prometheus depends_on（Step 4）
- [.github/workflows/ci.yml](../.github/workflows/ci.yml) — 新增 `helm-chart-lint` job（Step 7）
- [documentation/PROJECT_STATUS.md](PROJECT_STATUS.md) — 状态刷新（Step 8）

**删除**：
- [helm/hyperscale-lcm/files/alertmanager.yml](../helm/hyperscale-lcm/files/alertmanager.yml)（被 `alertmanager.yml.tpl` 取代，Step 3）

**不得触碰**：
- Core 所有 Java 代码与 [core/src/main/resources/application.properties](../core/src/main/resources/application.properties)
- Satellite 所有 Go 代码
- Frontend 所有 TS/React 代码
- [helm/hyperscale-lcm/templates/monitoring.yaml](../helm/hyperscale-lcm/templates/monitoring.yaml) 中的 `PrometheusRule` rule 定义（规则源分叉留作 follow-up）
- [infra/prometheus-alerts.yml](../infra/prometheus-alerts.yml) 的 rule 定义
- `lcm.proto` 与任何 gRPC 生成物
- JaCoCo 配置

## 不做的事

- 不迁移到 kube-prometheus-stack / Prometheus Operator
- 不合并 helm `PrometheusRule` 与 `infra/prometheus-alerts.yml` 两套规则源（follow-up）
- 不调整任何现有 rule 的表达式、阈值或 severity label
- 不引入 External Secrets Operator / HashiCorp Vault / SOPS 集成
- 不引入 Grafana OnCall / Incident.io / PagerDuty Event API v2 专有字段
- 不提供 CI 级别的"发真 Slack 消息"测试（secret 不应进 CI）
- 不在此 PR 内触碰 Core / Satellite / Frontend 任何代码
