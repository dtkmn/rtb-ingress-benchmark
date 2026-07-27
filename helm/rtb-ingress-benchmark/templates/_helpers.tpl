{{/*
Expand the name of the chart.
*/}}
{{- define "rtb-ingress-benchmark.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "rtb-ingress-benchmark.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "rtb-ingress-benchmark.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "rtb-ingress-benchmark.labels" -}}
helm.sh/chart: {{ include "rtb-ingress-benchmark.chart" . }}
{{ include "rtb-ingress-benchmark.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "rtb-ingress-benchmark.selectorLabels" -}}
app.kubernetes.io/name: {{ include "rtb-ingress-benchmark.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Apply the least-privilege controls required by every workload container.
The numeric user (and optional group) remain image-specific and configurable.
*/}}
{{- define "rtb-ingress-benchmark.containerSecurityContext" -}}
allowPrivilegeEscalation: false
capabilities:
  drop:
    - ALL
runAsNonRoot: true
runAsUser: {{ required "containerSecurityContext.runAsUser is required" .runAsUser }}
{{- if hasKey . "runAsGroup" }}
runAsGroup: {{ .runAsGroup }}
{{- end }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "rtb-ingress-benchmark.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "rtb-ingress-benchmark.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}
