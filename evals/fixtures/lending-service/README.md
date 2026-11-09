# lending-service (eval fixture)

A securities-lending service with seeded defects, for the scripted e2e (`evals/e2e/run-lending.sh`):

| Flow | Capability | Seeded defect |
|---|---|---|
| `borrow.book` (`POST /api/borrows`, Resilience4j circuit breaker) | rest-api | D19: an unreachable inventory books the borrow anyway |
| `recall.recall` (Spring Cloud Stream function) | messaging | D20: a recall larger than the borrow returns more shares than were borrowed |
| `fee-accrual.flow` (Spring Integration flow) | integration | D21: the daily fee divides by 365, not 360 |

The repository already runs Cucumber (`RunCucumberTest`, `CucumberSpringConfiguration`), so gta writes its component
scenarios as feature files. `inventory.api-key` comes from Secret Manager (`sm@`), and recalls travel over the Pub/Sub
binder; tests use a fake Secret Manager and the Cloud Stream test binder, never GCP.
