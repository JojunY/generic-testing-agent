---
name: gta-testing-scheduled-jobs
description: Tests @Scheduled, Quartz, ShedLock and Cloud Scheduler-triggered jobs without waiting for cron - the job logic directly, cron expressions with time zones and DST, scheduling wiring, no-overlap locking, idempotent re-runs and at-least-once HTTP triggers. Use when a gta author brief targets a scheduler scenario (P2).
---

# Testing scheduled jobs

## Quick start

Split trigger from logic:

```java
@Test
@Tag("scenario:invoice.run-nightly/s02")
@DisplayName("[invoice.run-nightly/s02] running twice does not bill twice")
void shouldBillOnceWhenRunTwice() {
    job.runNightly();            // call the job method directly
    job.runNightly();
    assertThat(invoices.countForDay(DAY)).isEqualTo(3);
}
```

## Workflow

1. Logic: call the job bean (or the service it delegates to) directly; never wait for the schedule.
2. Cron: table-driven tests of `CronExpression.parse(expr).next(t)` across time zones, DST changes, month ends and leap years (`references/cron.md`).
3. Wiring: one test with a short schedule property and `@MockitoSpyBean` + Awaitility proves the scheduler calls the job; other tests disable scheduling via a property.
4. Time: inject a fixed `Clock` bean when the job uses one; otherwise see the seams reference in the writing-tests skill.
5. Locking and re-runs: `references/shedlock.md`. Cloud Scheduler HTTP triggers: `references/cloud-scheduler.md`.

## Rules

- No `Thread.sleep`; Awaitility with an explicit `atMost`.
- A job must be idempotent per period: assert the end state after two runs.

## References

- `references/cron.md` · `references/shedlock.md` · `references/cloud-scheduler.md`
