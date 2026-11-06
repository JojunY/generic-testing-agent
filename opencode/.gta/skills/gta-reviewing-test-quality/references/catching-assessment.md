# Catching tests (PR mode, P3)

A catching test targets a risk a change introduces (a scenario with `hint.intent=catching`) and is expected to fail if
the change is buggy. When one fails reproducibly, gtaw applies its rules first, then you assess it.

## What gtaw has already decided

- Flaky failures (different results on re-runs) never reach you as catches.
- The base run: gtaw runs the test on the PR's base (the base's production code, this branch's tests). The triage brief
  says which:
  - **passes on the base**: the change made it fail. This is a catch candidate.
  - **fails on the base too**: the failure predates the change. It is not a catch, and gtaw refuses `product-defect`.
  - **new on this branch**: the code the scenario cites does not exist on the base, so judge from the spec and the PR.
  - **could not run**: judge from the diff.

## Your assessment

1. Does the PR description or an approved spec say the new behaviour is intended? Then `expected-change`.
2. Is the failing expectation one you would bet the change broke, given the PR and the spec? Then `product-defect`: a
   human confirms it before anything is pinned. This holds even for an `observed` expectation, because the human is the
   one who decides.
3. Is the PR silent about it? Then `spec-gap`: the PR author is asked.
4. A `spec-error` on a catching test also goes to a human. gtaw never auto-corrects it to the new behaviour, because that
   would hide the catch.

Only report catches you would bet on: noise destroys trust in catching tests. The report counts the confirmed ones as
catching hits.
