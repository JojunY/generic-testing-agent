# Property-style tests for invariants

Invariants ("For any …") are tested with many generated inputs and a recorded seed. Default tooling: JUnit `@ParameterizedTest` with Instancio-generated arguments. jqwik is not the default (from 1.10 it adds restrictive terms for coding agents); keep it only if the service already uses it, pinned below 1.10.

```java
static Stream<OrderRequest> requests() {
    return Instancio.of(OrderRequest.class)
            .withSeed(20261103L)                                  // record the seed: failures must be reproducible
            .set(field(OrderRequest::currency), "EUR")
            .stream().limit(50);
}

@ParameterizedTest
@MethodSource("requests")
@Tag("unit") @Tag("flow:order.create") @Tag("scenario:order.create/i01") @Tag("gta")
@DisplayName("[order.create/i01] same idempotency key creates one order")
void shouldCreateOneOrderWhenSubmittedTwice(OrderRequest request) {
    service.create(request, "key-1");
    service.create(request, "key-1");
    assertThat(repository.countByIdempotencyKey("key-1")).isEqualTo(1);
}
```

Name the property in the assertion, keep generators small, and constrain them to valid inputs unless the invariant is about invalid ones.
