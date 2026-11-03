# Restart tests

1. Fixture with a record that fails deterministically at position N (or a test bean that throws on the Nth write).
2. First run → `BatchStatus.FAILED`, committed chunks persisted.
3. Remove the failure cause (replace the bean or fixture line), restart with the same `JobParameters`.
4. Assert `COMPLETED`, the total written rows equal the input minus skips, and no row was written twice (unique business key count).

Also test: a second start with identical parameters after `COMPLETED` is rejected (`JobInstanceAlreadyCompleteException`) unless the job is designed to be re-runnable.
