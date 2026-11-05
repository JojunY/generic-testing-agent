# StepVerifier patterns

| Behaviour | Assertion |
|---|---|
| one value | `StepVerifier.create(mono).expectNext(expected).verifyComplete()` |
| several values in order | `.expectNext(a, b, c).verifyComplete()` |
| count only (large streams) | `.expectNextCount(1000).verifyComplete()` |
| empty | `StepVerifier.create(mono).verifyComplete()` |
| error | `.expectErrorMatches(e -> e instanceof ResponseStatusException r && r.getStatusCode().value() == 409).verify()` |
| timeout or delay | `StepVerifier.withVirtualTime(() -> service.slowCall()).thenAwait(Duration.ofSeconds(5)).expectNext(x).verifyComplete()` |
| backpressure | `StepVerifier.create(flux, 2).expectNext(a, b).thenRequest(1).expectNext(c).thenCancel().verify()` |

`withVirtualTime` only works when the publisher is created inside the supplier; a publisher built outside it already
uses the real clock.
