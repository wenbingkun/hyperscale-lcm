{{/*
Common labels
*/}}
{{- define "hyperscale-lcm.labels" -}}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Core selector labels
*/}}
{{- define "hyperscale-lcm.core.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}-core
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Frontend selector labels
*/}}
{{- define "hyperscale-lcm.frontend.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}-frontend
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Satellite selector labels
*/}}
{{- define "hyperscale-lcm.satellite.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}-satellite
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create image pull secrets
*/}}
{{- define "hyperscale-lcm.imagePullSecrets" -}}
{{- if .Values.global.imagePullSecrets }}
imagePullSecrets:
{{- range .Values.global.imagePullSecrets }}
  - name: {{ . }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Full image name with registry
*/}}
{{- define "hyperscale-lcm.image" -}}
{{- $registry := .Values.global.imageRegistry | default "" -}}
{{- if $registry -}}
{{ $registry }}/{{ .repository }}:{{ .tag }}
{{- else -}}
{{ .repository }}:{{ .tag }}
{{- end -}}
{{- end }}
