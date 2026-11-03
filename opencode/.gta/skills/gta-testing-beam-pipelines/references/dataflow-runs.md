# Real Dataflow runs (P4)

- One or two end-to-end runs per pipeline on Dataflow with temporary resources named `gta-<shortRunId>-<purpose>` (BigQuery datasets `gta_<shortRunId>_<purpose>`), TTL labels, and teardown.
- Only through `gtaw env` with `allowCloud=true`; never from component tests.
- Validate: row counts, schema, dead-letter contents, and a differential comparison with an independently computed result.
