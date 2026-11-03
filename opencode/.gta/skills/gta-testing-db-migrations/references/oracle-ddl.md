# Oracle DDL pitfalls

- Adding a `NOT NULL` column to a populated table needs a default or a backfill.
- Online index creation (`ONLINE`) and locking on large tables.
- Identifier length and case (quoted vs unquoted names).
- `NUMBER` precision changes truncate data silently.
- Sequences vs identity columns; `CACHE` and gaps.
- Implicit commits: Oracle DDL commits the current transaction — migration scripts mixing DML and DDL cannot be rolled back as a unit.
