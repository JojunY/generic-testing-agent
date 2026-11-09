---
name: gta-testing-resilience
description: Tests Resilience4j and Spring Retry behaviour locally - circuit breakers that open and close at their configured thresholds, fallbacks that fail closed, retries counted at a WireMock stub, time limiters against a delayed stub, with the registries reset between tests. Use when a gta author brief belongs to a service that uses Resilience4j or Spring Retry (P4).
---

# Testing resilience

## Quick start

```java
@SpringBootTest
class InventoryClientIT {
    @RegisterExtension
    static WireMockExtension inventory = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().http2PlainDisabled(true)) // see the WireMock skill
            .build();

    @DynamicPropertySource
    static void inventoryUrl(DynamicPropertyRegistry r) {
        r.add("inventory.url", inventory::baseUrl);
    }

    @Autowired InventoryClient client;
    @Autowired CircuitBreakerRegistry breakers;

    @BeforeEach
    void closeTheBreaker() {
        breakers.circuitBreaker("inventory").reset(); // breaker state survives in the cached context
    }

    @Test
    @Tag("flow:borrow.book") @Tag("scenario:borrow.book/s05")
    @DisplayName("[borrow.book/s05] the breaker opens after five failures and stops calling inventory")
    void shouldStopCallingInventoryWhenTheBreakerIsOpen() {
        inventory.stubFor(post("/reservations").willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 6; i++) {
            client.reserve("ACME", 100);
        }

        assertThat(breakers.circuitBreaker("inventory").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        inventory.verify(5, postRequestedFor(urlEqualTo("/reservations"))); // minimum-number-of-calls=5
    }
}
```

Checked on Spring Boot 4.1 with `resilience4j-spring-boot4` 2.4.

## Workflow

1. Read the configuration the behaviour depends on (`resilience4j.circuitbreaker.instances.<name>.*`,
   `resilience4j.retry.instances.<name>.*`, `@Retryable(maxAttempts = ...)`) and cite it in the spec with
   `ref=config:<key>`: the thresholds are the expected values.
2. Fallbacks: the spec says what the caller gets while the dependency is down. A fallback that turns an outage into
   success (a booking confirmed without a reservation) is a finding, not the expected behaviour.
3. Circuit breaker: drive the failures through a WireMock stub (5xx, `Fault.CONNECTION_RESET_BY_PEER`), then assert the
   state, the fallback result and that calls stop (`verify(n, ...)`). Half-open and closing: see
   `references/breaker-states.md`.
4. Retry: count the attempts at the stub (`verify(3, ...)`), and that a non-retryable error (a 4xx, if configured) is
   tried once.
5. Time limiter and timeouts: a stub with `withFixedDelay(...)` above the limit; assert the timeout path and keep the
   delay small (seconds).

## Rules

- Reset the registries (`CircuitBreakerRegistry`, `RetryRegistry`) in `@BeforeEach`, or each test sees the state the
  previous one left.
- Never wait out a real `wait-duration-in-open-state`: override it with a test property (`PT1S` or less).
- Failures come from the stub, never from an unreachable real host.

## References

- `references/breaker-states.md`
