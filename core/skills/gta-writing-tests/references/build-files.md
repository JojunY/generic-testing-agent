# Build file changes (foundation item only)

Allowed in `pom.xml`:

- `<dependency>` with `<scope>test</scope>`. Omit `<version>` when the Spring Boot parent manages it (spring-boot-starter-test, spring-kafka-test, h2, awaitility, assertj, mockito). Use a `<properties>` version for others (gta-junit, wiremock, instancio, artemis embedded server).
- `maven-failsafe-plugin` so `*IT` classes run in `mvn verify` in CI. Add the explicit includes below only when the pom has no Surefire/Failsafe configuration of its own; never rewrite the developer's configuration (`./gtaw doctor` reports a mismatch instead).

Not allowed: changing production dependencies, plugin versions of the build, compiler settings, profiles used for deployment.

All dependencies resolve through the company Nexus proxy; never add `<repositories>`.

Example (Boot 4.1):

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-test</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.wiremock</groupId>
  <artifactId>wiremock-standalone</artifactId>
  <version>${wiremock.version}</version>
  <scope>test</scope>
</dependency>
```

Explicit test selection, so no suffix relies on a plugin default (`naming.md`):

```xml
<plugin>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <includes><include>**/*Test.java</include></includes>          <!-- Test, SliceTest, PactTest -->
    <excludes><exclude>**/*E2ETest.java</exclude></excludes>       <!-- e2e profile only -->
  </configuration>
</plugin>
<plugin>
  <artifactId>maven-failsafe-plugin</artifactId>
  <configuration>
    <includes><include>**/*IT.java</include></includes>            <!-- IT, ContractIT -->
  </configuration>
  <executions><execution><goals><goal>integration-test</goal><goal>verify</goal></goals></execution></executions>
</plugin>
```
