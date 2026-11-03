# ShedLock

- ShedLock guarantees at most one execution at a time, not exactly one per period. Assert no overlap, and idempotency for re-runs.
- Unit tests of job methods annotated `@SchedulerLock` that call `LockAssert.assertLocked()`: wrap with `LockAssert.TestHelper.makeAllAssertsPass(true)`.
- Component test of the lock: start two threads calling the locked method through the Spring proxy against the shared lock table (Oracle shared schema or H2), assert one execution and that the second returns without running.
- `lockAtMostFor` must exceed the longest run; `lockAtLeastFor` prevents double runs across nodes with clock skew.
