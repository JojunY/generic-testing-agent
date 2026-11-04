---
name: gta-testing-spring-batch-jobs
description: Tests Spring Batch 5 and 6 jobs - readers, processors and writers in isolation, single steps, whole jobs with reconciliation counts and golden output, restart after failure without duplicates, and skip/retry policies with poison records. Use when a gta author brief targets a spring-batch scenario (P2).
---

# Testing Spring Batch jobs

## Quick start (Batch 6 / Boot 4)

```java
@SpringBatchTest
@SpringBootTest
@EnableJdbcJobRepository          // Batch 6 defaults to a resourceless repository: restart tests need JDBC
class InvoiceImportJobIT {
    @Autowired JobOperatorTestUtils jobs;

    @Test
    @Tag("scenario:invoice.import/s04")
    void shouldSkipPoisonRecordsWhenImporting() throws Exception {
        JobExecution run = jobs.startJob(new JobParametersBuilder().addString("file", "com/acme/invoice/batch/InvoiceImportJobConfigIT/s04.poison.csv").toJobParameters());
        StepExecution step = run.getStepExecutions().iterator().next();
        assertThat(run.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        assertThat(step.getSkipCount()).isEqualTo(2);
        assertThat(step.getReadCount()).isEqualTo(step.getWriteCount() + step.getSkipCount() + step.getFilterCount());
    }
}
```

## Workflow

1. Item components: unit-test readers, processors and writers; step-scoped beans with `StepScopeTestUtils`.
2. Steps: `jobs.startStep("importStep")` (Batch 6) / `launchStep` (Batch 5).
3. Jobs: assert `ExitStatus`, read/write/skip/filter counts and the reconciliation identity, and compare output to a golden file.
4. Restart: fail at record N (fixture), assert `FAILED`, restart with the same parameters, assert `COMPLETED` and no duplicates (`references/restart.md`).
5. Clean job metadata between tests with `JobRepositoryTestUtils`.

## Rules

- Refs include the step `@Bean` methods and skip/retry policy beans so policy changes mark the spec stale.
- Batch 5 vs 6 API differences: `references/batch5-vs-batch6.md`.

## References

- `references/batch5-vs-batch6.md` · `references/restart.md`
