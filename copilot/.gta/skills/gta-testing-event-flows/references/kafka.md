# Kafka specifics

- Consumer error handling: `DefaultErrorHandler` with `DeadLetterPublishingRecoverer`; assert the record lands on `<topic>.DLT` with headers explaining the failure.
- Offsets: after a failure and restart, the consumer resumes without losing or re-processing committed records.
- Ordering: only per partition; test that the key used guarantees the ordering the spec needs.
- Transactions (`transactional.id`): records sent in a rolled-back transaction are not visible to `read_committed` consumers.
