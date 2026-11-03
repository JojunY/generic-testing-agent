---
name: gta-testing-db-migrations
description: Tests Flyway and Liquibase migrations for Oracle - migrating from the last released schema with representative data, N-1 application compatibility during rolling deployments (expand/contract), and rollback scripts. Use when a gta author brief targets a db-migration scenario (P2).
---

# Testing database migrations

## Quick start

1. Start from the last released schema version (Flyway `target` / Liquibase tag) on the Oracle shared test schema.
2. Load representative data (fixtures, including edge values: nulls, max lengths, old enum values).
3. Run the new migrations; assert data is preserved and transformed as the spec says.
4. Run the previous application version's queries (N-1) against the new schema.

## Workflow

- Use the Oracle shared test schema; H2 cannot validate Oracle DDL (`references/oracle-ddl.md`).
- Flyway: `references/flyway.md`; Liquibase: `references/liquibase.md`.
- Expand/contract: new columns nullable or defaulted first, backfill, switch reads, drop later (`references/n-minus-1.md`).

## Rules

- Never run migrations against shared environments other than the per-run `GTA_<shortRunId>` schema.

## References

- `references/flyway.md` · `references/liquibase.md` · `references/oracle-ddl.md` · `references/n-minus-1.md`
