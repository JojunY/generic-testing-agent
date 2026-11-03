# Cost and cleanup

- `environments.allowCloud=false` by default; only a dedicated test project.
- Every resource: name prefix `gta-<shortRunId>-`, labels `gta-run-id` and `gta-ttl-hours`.
- Budgets: limit instances (`--max-instances=1`), Dataflow workers, BigQuery bytes billed.
- Teardown in `gtaw env down`; scheduled `gtaw env gc` as a backstop.
