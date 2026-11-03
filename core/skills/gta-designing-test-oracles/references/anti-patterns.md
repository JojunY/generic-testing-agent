# Oracle anti-patterns (rejected in review)

| Anti-pattern | Why it is wrong | Instead |
|---|---|---|
| Expected value copied from the actual output | Encodes the current bug as expected | Take it from the spec THEN |
| Only `isNotNull()` / `isNotEmpty()` | Passes for wrong values | Compare the exact value |
| Asserting a mock was called, but not the result | Tests wiring, not behaviour | Assert the observable outcome |
| Reading state back through the code under test | Same bug on both sides | Read through a repository or JDBC |
| `Thread.sleep` before asserting | Flaky and slow | Awaitility `await().atMost(...)` |
| `try { …; fail(); } catch (Exception e) {}` | Swallows the wrong exception | `assertThatThrownBy(...).isInstanceOf(...)` |
| `@Disabled`, `assumeTrue(false)` | Hides the failure | Let it fail; the reviewer triages |
| Broadening matchers after a failure (`contains` instead of `isEqualTo`, `ignoringFields`) | Weakens a locked oracle | Keep the strict assertion |
| Several scenarios in one method | One failure hides the others | One scenario per method |
| Test-managed `@Transactional` in component tests | Rollback hides commit-time behaviour | Clean up explicitly |
