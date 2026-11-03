# BigQuery

There is no faithful local BigQuery. In component tests:

- Test the code that builds queries and maps rows behind an interface (e.g. `ReportRepository`), and replace that interface with an in-memory fake in tests of the business logic.
- Assert the generated SQL text only for structure the spec cares about (table, partition filter, parameter binding), never for exact formatting.
- Real SQL semantics (functions, `MERGE`, partition pruning, costs) are tested in P4 system tests against a temporary dataset `gta_<shortRunId>_<purpose>`.

Report `fidelity: low` for BigQuery-dependent scenarios.
