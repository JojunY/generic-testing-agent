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

A stream that completes is read whole with `MockMvc` (verified on Boot 4.1):

```java
MvcResult started = mvc.perform(get("/api/quotes/sku-1/ladder").accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(request().asyncStarted())
        .andReturn();
String body = mvc.perform(asyncDispatch(started))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
List<String> data = body.lines().filter(l -> l.startsWith("data:")).map(l -> l.substring(5)).toList();
```

- Assert the number of events, their order and the payload fields the spec states (`event:` lines carry the names).
- An endless stream does not complete: start the application on a random port and read `take(n)` events with a
  streaming client instead.
- A timeout or a completed emitter is part of the behaviour: assert it when the spec says what happens.
