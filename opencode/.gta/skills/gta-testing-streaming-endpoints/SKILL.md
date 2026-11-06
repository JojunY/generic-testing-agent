---
name: gta-testing-streaming-endpoints
description: Tests WebSocket, STOMP and Server-Sent Events endpoints locally - a STOMP client against the random port for @MessageMapping and @SendTo, a plain WebSocket client for WebSocketHandler, WebTestClient or a streaming HTTP client for SseEmitter and Flux<ServerSentEvent>, with Awaitility instead of sleeps and the message order the spec states. Use when a gta author brief targets a websocket scenario (P4).
---

# Testing streaming endpoints

## Quick start

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PriceTickerControllerIT {
    @LocalServerPort int port;

    @Test
    @Tag("scenario:price.subscribe/s01")
    @DisplayName("[price.subscribe/s01] a subscriber receives the new price")
    void shouldPushTheNewPriceToSubscribers() throws Exception {
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new JacksonJsonMessageConverter());
        BlockingQueue<PriceUpdate> received = new LinkedBlockingQueue<>();
        StompSession session = stomp.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
        session.subscribe("/topic/prices", new FrameHandler(PriceUpdate.class, received));

        session.send("/app/prices", new PriceUpdate("sku-1", new BigDecimal("9.90")));

        assertThat(received.poll(5, TimeUnit.SECONDS)).isEqualTo(new PriceUpdate("sku-1", new BigDecimal("9.90")));
    }
}
```

## Workflow

1. Component layer: `@SpringBootTest(webEnvironment = RANDOM_PORT)`; connect a real client to `localhost` and the random port. Nothing leaves the machine.
2. STOMP (`@MessageMapping`, `@SendTo`, `SimpMessagingTemplate`): `WebSocketStompClient`, subscribe first, then send; collect frames in a `BlockingQueue`.
3. Raw WebSocket (`WebSocketHandler`): `StandardWebSocketClient` (servlet) or `ReactorNettyWebSocketClient` (WebFlux).
4. SSE (`SseEmitter`, `Flux<ServerSentEvent<T>>`, `text/event-stream`): `WebTestClient` with `.returnResult(...)`
   and `StepVerifier` for a `Flux`, or `WebClient` with `bodyToFlux(ServerSentEvent.class)` against the random port.
5. Unit layer: test the handler's logic directly (the method behind `@MessageMapping`), without a connection.
6. Assert what the spec states: the payload, the destination, the order of messages, and that a client without the
   right role or subscription receives nothing (security skill).

## Rules

- Wait with `poll(timeout)` or Awaitility, never `Thread.sleep`; a missing message must fail within seconds.
- Subscribe before you trigger the message, or the test races the broker.
- Close sessions and clients in the test (`try`-with-resources or `@AfterEach`).
- Connect to `localhost` only: never to SIT, UAT or production endpoints.

## References

- `references/sse.md`
