# Triage verdicts

| Verdict | Choose when | What gtaw does |
|---|---|---|
| `test-defect` | setup, input, wiring or mechanics of the test are wrong; the expected values are right | back to the author, oracle stays locked |
| `flaky` | gtaw saw different results on re-runs, or timing/order/shared state explains the failure | back to the author; only waiting/synchronisation may change |
| `product-defect` | the oracle is `confirmed`/`documented` and the code clearly violates it | a human confirms; then `@KnownDefect` + issue draft |
| `spec-error` | the expectation itself is wrong | observed/defaulted: THEN corrected from the actual value; confirmed/documented: a human decides |
| `spec-gap` | the spec misses a case needed to judge (e.g. which error code applies) | spec goes back to review |
| `oracle-defect` | the locked assertion is written wrongly (wrong field, wrong unit) | a human must unlock (`gtaw relock`) |
| `environment` | the failure comes from the local environment (emulator missing, port in use) | a human fixes the environment |
| `expected-change` | behaviour changed on purpose (e.g. the PR intends it) | a human updates the spec |

Reading the failure: start from the first non-framework stack frame; `TEST_SUT_ERROR` means the exception came from production code — usually `product-defect` or `spec-error`, rarely `test-defect`.
