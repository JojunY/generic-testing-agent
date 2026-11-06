# Server-Sent Events

## WebFlux (`Flux<ServerSentEvent<T>>`)

```java
FluxExchangeResult<ServerSentEvent<String>> result = web.get().uri("/api/orders/o-1/events")
        .accept(MediaType.TEXT_EVENT_STREAM)
        .exchange()
        .expectStatus().isOk()
        .returnResult(new ParameterizedTypeReference<>() {});

StepVerifier.create(result.getResponseBody().map(ServerSentEvent::data).take(2))
        .expectNext("PAID", "SHIPPED")
        .verifyComplete();
```

- Use `take(n)` for an endless stream, so `verifyComplete()` ends.
- Assert the event name and id too when the spec states them (`ServerSentEvent::event`, `::id`).

## Servlet (`SseEmitter`)

- `MockMvc`: `perform(get(...)).andExpect(request().asyncStarted())`, then `asyncDispatch`, and read the
  `text/event-stream` body (`data:` lines).
- Or start the application on a random port and read the stream with `WebClient`, as above.
- A timeout or a completed emitter is part of the behaviour: assert it when the spec says what happens.
