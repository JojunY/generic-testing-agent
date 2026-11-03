# N-1 compatibility

During a rolling deployment on GKE or Cloud Run, the previous application version runs against the new schema.

- Keep the previous release's repository/SQL tests (from the base branch tag) and run them after the new migrations.
- Expand → migrate data → contract in separate releases; a migration that renames or drops a column used by N-1 fails this test.
- Report incompatible migrations as findings with the release they must wait for.
