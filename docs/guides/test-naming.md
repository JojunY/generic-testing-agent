# Test naming and placement

One rule covers every test and test artifact, in this repository and in the services gta writes tests for:

> A test class is named `<SourceClass><Suffix>`. It lives in the **same package** as its source class, in the source
> root of its tool. Each suffix appears **at most once per source class**, so every test name maps back to exactly one
> source, and every source to at most one test of each kind.

Example: `controller/ExchangeReportController` →
- `src/test/java/.../controller/ExchangeReportControllerTest`
- `src/test/java/.../controller/ExchangeReportControllerSliceTest`
- `src/test/java/.../controller/ExchangeReportControllerIT`
- `src/test/karate/java/.../controller/ExchangeReportControllerKarate`

A test file grows with JUnit `@Nested` classes or more methods, never with a second file of the same suffix.

## Suffixes

| Kind | Tool | Suffix | Source root | Run by (configured explicitly) |
|---|---|---|---|---|
| Unit: no Spring context, no I/O | JUnit, Mockito, AssertJ | `Test` | `src/test/java` | Surefire, `test` phase |
| Spring slice | `@WebMvcTest`, `@WebFluxTest`, `@DataJpaTest`, `@JdbcTest`, `@JsonTest`, `@RestClientTest`, … | `SliceTest` | `src/test/java` | Surefire, `test` phase |
| Integration: full context, embedded or faked dependencies | `@SpringBootTest`, WireMock, embedded Kafka/JMS | `IT` | `src/test/java` | Failsafe, `integration-test` phase |
| Application seed (the context starts) | `@SpringBootTest` | `IT` on the `@SpringBootApplication` class | `src/test/java` | Failsafe |
| Contract, provider side | Spring Cloud Contract, Pact | `ContractIT` | `src/test/java` | Failsafe |
| Contract, consumer side | Pact | `PactTest` | `src/test/java` | Surefire |
| API / system | Karate | `Karate` | `src/test/karate/java` | `karate` profile only |
| End-to-end | JUnit against a running system | `E2ETest` | `src/test/e2e/java` | `e2e` profile only |
| Batch job, scheduler, listener | Spring Batch test, Awaitility, embedded broker | `IT` on the job configuration, scheduler or listener class | `src/test/java` | Failsafe |
| Beam pipeline | `TestPipeline` | `Test` | `src/test/java` | Surefire |
| Performance (later) | Gatling | `Simulation` | `src/test/gatling/java` | `gatling` profile |
| Architecture rules | ArchUnit | `ArchitectureTest` (exception E3) | `src/test/java/<base>/architecture/` | Surefire |

A slice test is named after the class it targets, never after the annotation: `OrderControllerSliceTest`, not
`OrderWebMvcTest`.

## Why selection is explicit

| Plugin | Default includes | Problem |
|---|---|---|
| Surefire | `**/Test*.java`, `**/*Test.java`, `**/*Tests.java`, `**/*TestCase.java` | Would also run `E2ETest`. |
| Failsafe | `**/IT*.java`, `**/*IT.java`, `**/*ITCase.java` | Fine for `IT` and `ContractIT`. |
| Both | – | Neither runs `Karate` or `Simulation`. |

So nothing relies on these defaults:
- **gtaw** passes `-Dtest` itself: `**/*Test,**/*IT,!**/*E2ETest`, or the item's own test class.
- **The service build**, when it has no Surefire/Failsafe configuration yet, gets it from the foundation item:
  - Surefire `includes` = `**/*Test.java`, `excludes` = `**/*E2ETest.java`;
  - Failsafe `includes` = `**/*IT.java`;
  - `karate` and `e2e` profiles add their source roots with `build-helper-maven-plugin`.

  An existing configuration is never rewritten (`gtaw doctor` warns when Failsafe is missing). The snippet is in
  `core/skills/gta-writing-tests/references/build-files.md`.
- **This repository** sets Surefire `includes` to `**/*Test.java`. The toolkit has unit tests only.

## Support code (never a test suffix, so no runner picks it up)

| Kind | Suffix | Placement |
|---|---|---|
| Fake: a working, lightweight implementation of a port | `Fake` | same package as the port: `domain/OrderRepository` → `domain/OrderRepositoryFake` |
| Stub of a downstream system | `Stub` | `<base>/support/stub/`: `PaymentGatewayStub` |
| Mock | no class: inline Mockito | – |
| Test data builder | `TestData` | same package as the built type: `domain/OrderTestData` |
| Test configuration | `TestConfig` | same package as the configuration it replaces: `config/ClockTestConfig` |
| JUnit extension | `Extension` | `<base>/support/`: `EmbeddedOracleExtension` |

## Test resources

| Kind | Path |
|---|---|
| Fixtures of one test class | `src/test/resources/<package path>/<TestClass>/<sNN>.<role>.<ext>`, e.g. `com/acme/order/api/OrderControllerIT/s01.request.json` |
| Golden (approved) output | the same folder, `<sNN>.approved.<ext>` |
| WireMock files | WireMock's own layout: `src/test/resources/wiremock/<downstream>/mappings/` and `__files/` |
| Spring test properties | `src/test/resources/application-test.yml` |
| Karate features | `src/test/karate/resources/<package path>/<SourceClass>Karate/<flow-name>.feature` |

## Generated artifacts

| Artifact | Path | Tracked |
|---|---|---|
| Behaviour spec | `.gta/specs/<domain>/<flow>.spec.md` | yes |
| Oracle shard | `.gta/specs/<domain>/<flow>.oracles.json` | yes |
| Test case ledger | `.gta/specs/<domain>/<flow>.cases.md`; overview `.gta/specs/INDEX.md` | yes |
| Escalation and defect records | `.gta/escalations/<itemKey>.md` | yes |
| Session reports | `.gta/reports/<yyyy-MM-dd>-<sessionId>.md` | yes |
| Working state, plan, briefs, runs, snapshots, PR comment draft | `.gta/work/**` (temporary) | no |
| Docs diagrams (this repository) | `docs/diagrams/<name>-{light,dark}.svg` from `scripts/diagrams/render.py` | yes |

## How gta applies the rule in a service

```
source  = outer class of the flow's entry point (method:com.acme.order.api.OrderController#create)
test    = <source root of the layer>/<source package>/<SourceClass><Suffix>.java
fixture = src/test/resources/<source package>/<SourceClass><Suffix>/
seed    = <@SpringBootApplication class>IT (one per application)
tags    = class: layer + gta; method: flow:<flowId> + scenario:<scenarioId>
```

## Exceptions

| # | Case | Rule |
|---|---|---|
| E1 | Types with no behaviour of their own: records and DTOs, enums without logic, interfaces, annotations, exception classes | No own test; they are tested through their users. In this repository they are listed in `toolkit/test-mirror-allowlist.txt` with the reason. |
| E2 | Nested classes | Tested in the outer class's test. |
| E3 | Tests with no single source (architecture rules, repository layout, the scripted e2e) | Named after their subject and listed in the allowlist. |
| E4 | A test file grows too big | Use `@Nested` per method or flow. If it is still too big, split the source, not the test. |
| E5 | The test class gta would write already exists and is not a gta test (no `@Tag("gta")`) | gtaw stops that item and asks: A) let gta add its tests to that file; B) skip it (the item is cancelled); C) the developer renamed or moved the file, check again. There is no default; the other items run first. |
| E6 | Tool-mandated layouts (WireMock, Spring, Karate classpath) | The tool's layout wins. |
| E7 | Generated production sources (OpenAPI, MapStruct, Lombok output) | No tests. |
| E8 | Default package | The test goes in the default package too. |
| E9 | A flow whose spec refers to no class | Named after the flow in the base package (`<Domain><Flow><Suffix>`), since there is no source to mirror. |
