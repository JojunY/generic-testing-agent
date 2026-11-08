# Naming and placement

A test class is named `<SourceClass><Suffix>` and lives in the source class's package. The brief gives the exact name;
these are the rules behind it.

| Kind | Suffix | Source root | Example for `api/BorrowController` |
|---|---|---|---|
| Unit (no Spring context, no I/O) | `Test` | `src/test/java` | `api/BorrowControllerTest` |
| Spring slice (`@WebMvcTest`, `@DataJpaTest`, `@JsonTest`, …) | `SliceTest` | `src/test/java` | `api/BorrowControllerSliceTest` |
| Component (full context, embedded or faked dependencies) | `IT` | `src/test/java` | `api/BorrowControllerIT` |
| Contract, provider side | `ContractIT` | `src/test/java` | `api/BorrowControllerContractIT` |
| Contract, consumer side (Pact) | `PactTest` | `src/test/java` | `client/PaymentClientPactTest` |
| System (Karate runner) | `Karate` | `src/test/karate/java` | `api/BorrowControllerKarate` |
| End-to-end | `E2ETest` | `src/test/e2e/java` | `api/BorrowControllerE2ETest` |
| Application seed (context starts) | `IT` on the `@SpringBootApplication` class | `src/test/java` | `StockLoanApplicationIT` |

- One file per source class and suffix. Several flows of one controller share its test class; tag each method with its
  `flow:` and `scenario:` and keep a flow's methods together. Never create a second file with the same suffix.
- A slice test is named after the class it targets, not the annotation (`BorrowControllerSliceTest`, not `BorrowWebMvcTest`).
- Support code never ends in a test suffix, so no runner picks it up: `<Port>Fake` (same package as the port),
  `<Type>TestData` (same package as the type), `<Downstream>Stub` and `<Name>Extension` in `<base>/support/`,
  `<Name>TestConfig` (same package as the configuration it replaces). Mocks are inline Mockito, never classes.
- Fixtures: `src/test/resources/<package path>/<TestClass>/<sNN>.<role>.<ext>` (`s01.request.json`, `s01.expected.json`,
  `s03.approved.json`). WireMock files keep WireMock's layout: `src/test/resources/wiremock/<downstream>/mappings/`.
- If the class the brief names already exists and is not a gta test, stop: gtaw asks the developer first.
