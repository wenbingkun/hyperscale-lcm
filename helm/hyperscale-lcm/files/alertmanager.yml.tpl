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
