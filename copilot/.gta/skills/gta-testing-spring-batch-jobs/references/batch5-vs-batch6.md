# Spring Batch 5 (Boot 3.5) vs 6 (Boot 4)

| | Batch 5 | Batch 6 |
|---|---|---|
| Test utility | `JobLauncherTestUtils.launchJob/launchStep` | `JobOperatorTestUtils.startJob/startStep` (`JobLauncherTestUtils` deprecated) |
| Default job repository | JDBC (with a DataSource) | `ResourcelessJobRepository`; enable JDBC with `@EnableJdbcJobRepository` (Boot 4: `spring-boot-starter-batch-jdbc`) |
| JUnit | 5 | 6 (JUnit 4 support removed) |

Restartability and "no duplicate run" tests only mean something with a persistent (JDBC) job repository.
