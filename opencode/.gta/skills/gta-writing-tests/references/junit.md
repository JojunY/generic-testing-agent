# JUnit conventions

- Imports: `org.junit.jupiter.api.*`, `org.junit.jupiter.params.*`, AssertJ `org.assertj.core.api.Assertions.*` (managed by the Boot parent).
- Lifecycle: `@BeforeEach` for per-test data; avoid `static` mutable state; tests must pass in any order (gtaw runs them in random order and under `Asia/Kolkata` timezone and `de-DE` locale).
- Parameterised tests: `@ParameterizedTest` + `@MethodSource`/`@CsvSource`; each row is a case of the same scenario.
- Timeouts: `@Timeout` only as a safety net; asynchronous waiting uses Awaitility.
- Tags are case-sensitive and may contain `:` `/` `.`; they must not contain spaces, `,`, `(`, `)`, `&`, `|`, `!`.
- Do not use `@TestMethodOrder` to make tests depend on each other.

Tag filter used by gtaw: `-Dgroups=scenario:<scenarioId>` for one scenario, `-Dgroups=gta` for all gta tests.
