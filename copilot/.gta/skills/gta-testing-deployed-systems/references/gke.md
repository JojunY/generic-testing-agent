# GKE

- One namespace per run: `gta-<shortRunId>`, created by `gtaw env up` from the service's Helm chart or manifests with test values.
- Wait for readiness probes before running scenarios; collect pod logs into the run directory on failure.
- Delete the namespace in `gtaw env down`; `gtaw env gc` deletes namespaces whose `gta-ttl-hours` expired.
