# Build file changes (foundation item only)

Allowed in `pom.xml`:

- `<dependency>` with `<scope>test</scope>`. Omit `<version>` when the Spring Boot parent manages it (spring-boot-starter-test, spring-kafka-test, h2, awaitility, assertj, mockito). Use a `<properties>` version for others (gta-junit, wiremock, instancio, artemis embedded server).
- `maven-failsafe-plugin` so `*IT` classes run in `mvn verify` in CI; `maven-surefire-plugin` configuration that does not exclude gta tests.

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
