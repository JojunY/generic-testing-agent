# Spring Boot 3.5 vs 4.x (test-relevant differences)

gtaw reads the parent version and states it in the brief.

| Topic | Boot 3.5 (JUnit 5) | Boot 4.x (JUnit 6) |
|---|---|---|
| Mock beans | `@MockBean` deprecated, prefer `@MockitoBean` | `@MockBean` removed → `@MockitoBean` / `@MockitoSpyBean` (`org.springframework.test.context.bean.override.mockito`) |
| MockMvc in `@SpringBootTest` | auto-configured with `@AutoConfigureMockMvc` | add `@AutoConfigureMockMvc` explicitly; test slices moved to per-technology modules (e.g. `spring-boot-webmvc-test`, package `org.springframework.boot.webmvc.test.autoconfigure`) |
| `TestRestTemplate` | auto-configured for `RANDOM_PORT` | add `@AutoConfigureTestRestTemplate` |
| AssertJ MockMvc | `MockMvcTester` (Spring Framework 6.2+) | `MockMvcTester` |
| Spring Batch | 5.x: `JobLauncherTestUtils` | 6.x: `JobOperatorTestUtils`; default `ResourcelessJobRepository` (use JDBC repository for restart tests) |
| JUnit | 5.x | 6.x (Java 17 baseline; same `org.junit.jupiter.api` packages) |

When an import does not resolve, check the module/package for the service's Boot version instead of adding a new dependency version.
