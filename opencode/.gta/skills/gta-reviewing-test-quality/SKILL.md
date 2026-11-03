---
name: gta-reviewing-test-quality
description: Judges gta tests as an independent, read-only reviewer - oracle review of passed tests against their scenario, and triage of failing tests into test-defect, flaky, product-defect, spec-error, spec-gap, oracle-defect, environment or expected-change. Use when a gta review brief asks for a verdict.
---

# Reviewing test quality

## Quick start

Answer with exactly one line: `verdict: <verdict> — <one-sentence reason>`.

## Workflow

1. Read the scenario (WHEN/THEN), the oracle source and the test method tagged `scenario:<id>`.
2. Oracle review (`references/oracle-review.md`): does each THEN have a strict assertion on the right object, and do the inputs really produce the WHEN? `accept` or `reject`.
3. Triage (`references/triage.md`): find the first cause of the failure and pick one verdict.
4. For catching tests in PR mode (P3): `references/catching-assessment.md`.

## Rules

- You never change files or run commands.
- `product-defect` only for `confirmed`/`documented` oracles; a wrong `observed`/`defaulted` expectation is `spec-error` (gtaw corrects it from the actual value and lists it for re-confirmation).
- Never suggest replacing an expected value with the actual value for a confirmed or documented oracle.
- Flag changed inputs/fixtures after an assertion failure: tests must not be bent to pass.

## References

- `references/oracle-review.md` · `references/triage.md` · `references/catching-assessment.md` · `references/mutation.md`
