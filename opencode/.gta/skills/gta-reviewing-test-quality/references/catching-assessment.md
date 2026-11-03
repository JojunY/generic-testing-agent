# Catching tests (PR mode, P3)

A catching test targets a risk introduced by a change and is expected to fail if the change is buggy.

When one fails:

1. Is the failure caused by the change under review (not by an unrelated flaky dependency)?
2. Does the PR description or a confirmed spec say the new behaviour is intended? If yes → `expected-change`.
3. Is the failing expectation backed by `confirmed`/`documented` behaviour? If yes → `product-defect` candidate; otherwise → `spec-gap` (ask the PR author).

Only report candidates you would bet on; noise destroys trust in catching tests.
