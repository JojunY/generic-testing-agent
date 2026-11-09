---
name: gta-testing-integration-flows
description: Tests Spring Integration flows locally - send to the flow's input channel, read what it publishes from a QueueChannel, assert what its filters drop, its transformers produce and its error channel receives, with @SpringIntegrationTest to keep inbound adapters from starting on their own. Use when a gta author brief targets an integration scenario (P4).
---

# Testing integration flows

## Quick start

```java
@SpringBootTest
@SpringIntegrationTest
class FeeAccrualIntegrationConfigIT {
    @Autowired @Qualifier("accrualRequests") MessageChannel accrualRequests;
    @Autowired QueueChannel accruals;

    @BeforeEach
    void empty() {
        accruals.clear(); // the context, and its channels, are shared by the tests of the class
    }

    @Test
    @Tag("flow:fee-accrual.flow") @Tag("scenario:fee-accrual.flow/s01")
    @DisplayName("[fee-accrual.flow/s01] a borrow at a zero rate accrues nothing")
    void shouldAccrueNothingWhenTheRateIsZero() {
        accrualRequests.send(MessageBuilder.withPayload(new AccrualRequest("b-1", 10_000, new BigDecimal("50.00"), 0)).build());

        assertThat(accruals.receive(200)).isNull();
    }

    @Test
    @Tag("flow:fee-accrual.flow") @Tag("scenario:fee-accrual.flow/s02")
    @DisplayName("[fee-accrual.flow/s02] the daily fee uses Act/360")
    void shouldUseAct360WhenADayAccrues() {
        accrualRequests.send(MessageBuilder.withPayload(new AccrualRequest("b-1", 10_000, new BigDecimal("50.00"), 25)).build());

        Message<?> published = accruals.receive(2000);
        assertThat(published).isNotNull();
        assertThat(((DailyFee) published.getPayload()).fee()).isEqualByComparingTo("3.47");
    }
}
```

Checked on Spring Boot 4.1 (Spring Integration 7.1).

## Workflow

1. The flow is the `@Bean IntegrationFlow`. Find its input (`IntegrationFlow.from("<channel>")` or an inbound adapter),
   its steps (filter, transform, split, route, handle) and its output channel or outbound adapter.
2. Stimulus: send to the input channel, injected by name with `@Qualifier("<channel>")`. For an inbound adapter
   (poller, JMS, file, Pub/Sub), send to the channel it feeds instead and stop the adapter with
   `@SpringIntegrationTest(noAutoStartup = "<adapter bean name pattern>")`.
3. Output: a `QueueChannel` can be read with `receive(timeout)`. When the flow ends in a direct channel or an outbound
   adapter, see `references/outputs.md`.
4. One scenario per branch the spec names: what a filter drops (`receive` with a short timeout returns `null`), each
   route of a router, a split into several messages (receive each), and the error channel when a step throws.
5. Brokers behind adapters come from `gta-using-embedded-dependencies` (embedded Artemis, the Pub/Sub emulator).

## Rules

- Clear queue channels in `@BeforeEach`: the Spring context and its channels are cached across tests.
- Wait with `receive(timeout)` or Awaitility, never `Thread.sleep`; a short timeout (200 ms) only proves absence.
- Assert the payload (and the headers the spec names), not only that a message arrived.

## References

- `references/outputs.md`
