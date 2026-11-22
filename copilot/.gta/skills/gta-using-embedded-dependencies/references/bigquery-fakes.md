# BigQuery

There is no faithful local BigQuery. In component tests:

- Test the code that builds queries and maps rows behind an interface (e.g. `ReportRepository`), and replace that interface with an in-memory fake in tests of the business logic.
- Assert the generated SQL text only for structure the spec cares about (table, partition filter, parameter binding), never for exact formatting.
- Real SQL semantics (functions, `MERGE`, partition pruning, costs) need a real BigQuery, and gta does not test deployed environments: the team covers them in SIT/UAT.

Report `fidelity: low` for BigQuery-dependent scenarios.
