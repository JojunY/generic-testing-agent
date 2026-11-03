# Oracle

## Preferred: shared test schema (high fidelity)

Configured by the team in `.gta/config.json`:

```json
{ "environments": { "oracle": { "testUrl": "jdbc:oracle:thin:@//oracle-test.internal:1521/TESTPDB",
                                "userEnv": "GTA_ORACLE_USER", "passwordEnv": "GTA_ORACLE_PASSWORD" } } }
```

- Each run uses its own schema `GTA_<shortRunId>` (uppercase, `-` → `_`), created by the foundation support class and dropped in `@AfterAll`; leftovers are cleaned by the team's periodic job.
- Credentials come only from the environment variables named in the config; never in files.
- Run Flyway/Liquibase migrations into the schema so the tests see the real DDL.

## Fallback: H2 in Oracle mode (low fidelity)

```properties
spring.datasource.url=jdbc:h2:mem:orders;MODE=Oracle;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH
```

Not faithful: PL/SQL, `MERGE` variants, sequences with caching, hierarchical queries, `NUMBER` precision, `DATE` vs `TIMESTAMP`, locking (`SELECT … FOR UPDATE SKIP LOCKED`), case-sensitive identifiers. Scenarios that depend on these get `fidelity: low` and the report asks for the shared schema.
