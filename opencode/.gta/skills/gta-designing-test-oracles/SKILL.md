---
name: gta-designing-test-oracles
description: Chooses and writes the assertions (oracle) that prove a gta scenario's THEN clauses - state, response, message, golden-file and property-style oracles - so a test fails for the right reason and every expected value from the spec appears in an assertion. Use when writing or reviewing any gta test.
---

# Designing test oracles

## Quick start

For each THEN clause write one assertion that names the observable result and contains the expected value **from the spec**:

```java
assertThat(response).hasStatus(503);                                   // THEN the response status is `503`
assertThat(orderRepository.findById(id)).get()
        .extracting(Order::getStatus).isEqualTo(OrderStatus.PENDING_PAYMENT); // THEN orders.status is `PENDING_PAYMENT`
assertThat(outbox.messagesTo("order-created")).isEmpty();               // AND no message to `order-created`
```

gtaw checks that every backtick value of the THEN (`503`, `PENDING_PAYMENT`, `order-created`) appears in an assertion of the method tagged `scenario:<id>`. `HttpStatus.SERVICE_UNAVAILABLE`, `status().isServiceUnavailable()` and `503` all count as `503`.

## Workflow

1. Map THEN → oracle type (`hint.oracle` in the scenario):
   - `response`: status, headers, body fields
   - `state`: what is persisted (read it back through a repository or JDBC, not through the code under test)
   - `message`: what was published (captured from an embedded broker or a fake)
   - `golden`: large outputs compared to an approved file (`references/golden-files.md`)
   - `property`: invariants over generated inputs (`references/property-inputs.md`)
2. Assert the side effects that must **not** happen too (no message, no row, no downstream call).
3. Make the assertion fail on the wrong value: compare exact values, not `isNotNull()`.

## Rules

- The expected value comes from the spec; never copy it from a run's actual output.
- One scenario per test method; a scenario may need several assertions.
- No `assertTrue(x.equals(y))`, no `assertNotNull` as the only check, no catching `AssertionError`.
- See `references/anti-patterns.md` before submitting.

## References

- `references/golden-files.md` · `references/property-inputs.md` · `references/anti-patterns.md`
