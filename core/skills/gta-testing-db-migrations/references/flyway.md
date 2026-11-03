# Flyway

```java
Flyway old = Flyway.configure().dataSource(ds).target(MigrationVersion.fromVersion("41")).load();
old.migrate();
loadFixture("fixtures/db/v41-orders.sql");
Flyway current = Flyway.configure().dataSource(ds).load();
MigrateResult result = current.migrate();
assertThat(result.success).isTrue();
assertThat(jdbc.queryForObject("select count(*) from orders where status is null", Integer.class)).isZero();
```

Oracle needs the `flyway-database-oracle` module.
