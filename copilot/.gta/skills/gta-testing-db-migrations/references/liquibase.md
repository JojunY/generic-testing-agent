# Liquibase

- Update to the released tag (`liquibase.update(new Contexts(), "v41")` or the tag command), load data, then update to head.
- Test `rollback` for changesets that declare one; assert the data survives the round trip where the spec requires it.
- Assert `DATABASECHANGELOG` checksums are unchanged for released changesets (editing released changesets is a finding).
