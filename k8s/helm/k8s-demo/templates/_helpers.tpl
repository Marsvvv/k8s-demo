{{/*
标准命名辅助函数
*/}}
{{- define "k8s-demo.fullname" -}}
{{- printf "%s" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "k8s-demo.labels" -}}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "k8s-demo.image" -}}
{{ .Values.image.registry }}/{{ .imageName }}:{{ .Values.image.tag }}
{{- end -}}
