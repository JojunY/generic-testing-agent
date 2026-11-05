---
name: gta-testing-reactive-apis
description: Tests Spring WebFlux endpoints and reactive code - WebTestClient against the running context, StepVerifier for Mono and Flux pipelines, error signals mapped to statuses, backpressure and empty results, timeouts with virtual time, and never block() in production paths. Use when a gta author brief targets a webflux scenario (P3).
---

# Testing reactive APIs

## Quick start

```java
@SpringBootTest
@AutoConfigureWebTestClient
class ProductControllerIT {
    @Autowired WebTestClient web;

    @Test
    @Tag("scenario:product.reserve/s02")
    @DisplayName("[product.reserve/s02] a reservation larger than the stock is refused")
    void shouldRefuseAReservationLargerThanTheStock() {
        web.post().uri("/api/products/p-2/reserve?quantity=3").exchange().expectStatus().isEqualTo(409);
    }
}
```

## Workflow

1. Component layer: `@SpringBootTest` with `@AutoConfigureWebTestClient` (Boot 4: `org.springframework.boot.webtestclient.autoconfigure`); slice layer: `@WebFluxTest(ProductController.class)` with the collaborators as `@MockitoBean`.
2. Assert the response the spec states: `expectStatus()`, `expectBody().jsonPath(...)`, or `expectBodyList(Product.class)` for a `Flux`.
3. Unit layer: test the pipeline with `StepVerifier.create(publisher).expectNext(...).verifyComplete()`; an error signal is `expectErrorMatches(...)`, an empty `Mono` is `verifyComplete()` with no `expectNext`.
4. Time: delays, retries and timeouts use `StepVerifier.withVirtualTime(() -> ...)` and `thenAwait(Duration)`, never real waiting.
5. Error mapping: an exception signal must become the status the spec states (`ResponseStatusException`, `@ExceptionHandler`); assert both the status and that nothing was stored.
6. Calls to downstream services: stub them with WireMock (stubbing skill) and point the `WebClient` base URL at it.

## Rules

- Never `block()` in a test of a reactive endpoint; use WebTestClient or StepVerifier, so the test runs on the same scheduler rules as production.
- A `Flux` result is asserted in full (order and size), not only its first element.
- `StepVerifier` always ends with a `verify...` call; without it nothing is subscribed and the test proves nothing.

## References

- `references/stepverifier.md`
