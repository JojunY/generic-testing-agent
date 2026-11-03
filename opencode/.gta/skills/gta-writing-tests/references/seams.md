# When production code has no test seam

gta never changes production code. Use existing seams, in this order:

1. **Replace a bean**: `@MockitoBean` / `@MockitoSpyBean` for a collaborator, or a `@TestConfiguration` bean that overrides it (e.g. a fixed `Clock` bean when the code injects `Clock`).
2. **Configuration**: `@DynamicPropertySource` / `@TestPropertySource` for URLs, timeouts, feature flags.
3. **Boundary stubs**: WireMock for HTTP, embedded brokers and databases (see `gta-using-embedded-dependencies`).
4. **Move up a layer**: if a unit has no seam (e.g. `LocalDateTime.now()` inside a method), test the behaviour at component level where the input can be controlled, and assert the observable effect.

When none works, write the best test possible at a higher layer and end your result with "refactor suggested: <what seam is missing>" so it appears in the report.
