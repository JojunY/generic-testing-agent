---
name: gta-testing-event-flows
description: Tests message-driven flows over Kafka, Pub/Sub and MQ (JMS) without containers - consumers and producers with embedded brokers or emulators, Awaitility instead of sleeps, duplicate and out-of-order delivery, poison messages and dead-letter handling, transactional outbox, and Spring application events. Use when a gta author brief targets a messaging scenario (P1).
---

# Testing event flows

## Quick start

```java
@Test
@Tag("scenario:payment.on-order-created/s03")
void shouldProcessOnceWhenMessageIsDeliveredTwice() {
    template.send("order-created", "ord-1", payload);
    template.send("order-created", "ord-1", payload);           // duplicate delivery
    await().atMost(Duration.ofSeconds(10))
           .untilAsserted(() -> assertThat(payments.countByOrder("ord-1")).isEqualTo(1));
}
```

## Workflow

1. Broker per technology (see `gta-using-embedded-dependencies`): `@EmbeddedKafka`, Pub/Sub emulator, embedded Artemis for JMS.
2. For every consumer test: duplicate delivery, out-of-order delivery, poison message → DLQ/backout, retry with backoff, restart/rebalance where relevant.
3. For producers: what is published (and not published) after commit and after rollback (outbox).
4. In-process events: Spring Modulith `Scenario` or `@RecordApplicationEvents` (`references/modulith-scenario.md`).

## Rules

- Never `Thread.sleep`; Awaitility with an explicit `atMost` (`references/awaitility.md`).
- Assert the end state, not only that a listener method was called.

## References

- `references/awaitility.md` · `references/pubsub-emulator-limits.md` · `references/kafka.md` · `references/jms.md` · `references/modulith-scenario.md`
