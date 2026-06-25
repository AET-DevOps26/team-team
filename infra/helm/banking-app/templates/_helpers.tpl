{{/*
Rollout checksum – forces a new ReplicaSet when config, secrets, or code changes.
Combines configmap + secret + git commit SHA so any change triggers a rollout.
*/}}
{{- define "banking-app.rolloutChecksum" -}}
{{ include (print $.Template.BasePath "/configmap.yaml") . | sha256sum }}{{ include (print $.Template.BasePath "/postgres-secret.yaml") . | sha256sum }}{{ .Values.gitCommitSha }}{{ .Values.deployTimestamp }}
{{- end -}}
