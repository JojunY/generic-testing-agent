# Application events

- Spring Modulith: `@ApplicationModuleTest` with a `Scenario` parameter: `scenario.stimulate(() -> service.book(request)).andWaitForEventOfType(BorrowBooked.class).toArriveAndVerify(e -> ...)`.
- Without Modulith: `@RecordApplicationEvents` + `ApplicationEvents` to assert published events.
- `@TransactionalEventListener(phase = AFTER_COMMIT)` only fires after a real commit: do not use test-managed transactions.
