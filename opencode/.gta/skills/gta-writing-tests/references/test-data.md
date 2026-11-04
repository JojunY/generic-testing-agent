# Test data

- Build inputs with small builders or Instancio; fix the seed (`Instancio.of(X.class).withSeed(42L)`) so failures reproduce.
- Use unique keys per test (`"ord-" + UUID.randomUUID()`) when the database or broker is shared across tests.
- Fixtures: `src/test/resources/<package path>/<TestClass>/<sNN>.<role>.json` (e.g. `com/acme/order/api/OrderControllerIT/s01.request.json`); golden output `<sNN>.approved.json`. Fixtures are watched inputs: changing them after an assertion failure goes to the reviewer.
- Never copy production data; no personal data (names, emails, account numbers) — generate it.
- Time: inject a fixed `Clock` where the code accepts one; otherwise control time through the seam described in `seams.md`.
- Clean up explicitly in `@AfterEach` when the test commits data (component tests do not use test-managed `@Transactional`).
