# Property-style tests for invariants

Invariants ("For any …") are tested with many generated inputs and a recorded seed. Default tooling: JUnit `@ParameterizedTest` with Instancio-generated arguments. jqwik is not the default (from 1.10 it adds restrictive terms for coding agents); keep it only if the service already uses it, pinned below 1.10.

```java
static Stream<BorrowRequest> requests() {
    return Instancio.of(BorrowRequest.class)
            .withSeed(20261103L)                                  // record the seed: failures must be reproducible
            .set(field(BorrowRequest::currency), "EUR")
            .stream().limit(50);
}

@ParameterizedTest
@MethodSource("requests")
@Tag("unit") @Tag("flow:borrow.book") @Tag("scenario:borrow.book/i01") @Tag("gta")
@DisplayName("[borrow.book/i01] same idempotency key books one borrow")
void shouldBookOneBorrowWhenSubmittedTwice(BorrowRequest request) {
    service.create(request, "key-1");
    service.create(request, "key-1");
    assertThat(repository.countByIdempotencyKey("key-1")).isEqualTo(1);
}
```

Name the property in the assertion, keep generators small, and constrain them to valid inputs unless the invariant is about invalid ones.
