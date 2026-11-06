---
name: gta-testing-streaming-endpoints
description: Tests WebSocket, STOMP and Server-Sent Events endpoints locally - a STOMP client against the random port for @MessageMapping and @SendTo, a plain WebSocket client for WebSocketHandler, WebTestClient or a streaming HTTP client for SseEmitter and Flux<ServerSentEvent>, with Awaitility instead of sleeps and the message order the spec states. Use when a gta author brief targets a websocket scenario (P4).
---

# Testing streaming endpoints

## Quick start

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QuoteControllerIT {
    @LocalServerPort int port;

    /** Subscribe, then send; resend the (idempotent) request until the first frame arrives. */
    private Quote quote(QuoteRequest request) throws Exception {
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new JacksonJsonMessageConverter()); // Jackson 3 (Spring 7, Boot 4)
        BlockingQueue<Quote> received = new LinkedBlockingQueue<>();
        StompSession session = stomp.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
        try {
            session.subscribe("/topic/quotes", new StompFrameHandler() {
                @Override public Type getPayloadType(StompHeaders headers) { return Quote.class; }
                @Override public void handleFrame(StompHeaders headers, Object payload) { received.add((Quote) payload); }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            Quote quote = null;
            while (quote == null && System.nanoTime() < deadline) {
                session.send("/app/quote", request);
                quote = received.poll(250, TimeUnit.MILLISECONDS);
            }
            return quote;
        } finally {
            session.disconnect();
            stomp.stop();
        }
    }

    @Test
    @Tag("flow:quote.request-quote") @Tag("scenario:quote.request-quote/s02")
    @DisplayName("[quote.request-quote/s02] ten items get the bulk discount")
    void shouldGiveTheBulkDiscountWhenTenItems() throws Exception {
        Quote quote = quote(new QuoteRequest("sku-1", 10, new BigDecimal("5.00")));

        assertThat(quote).isNotNull();
        assertThat(quote.total()).isEqualByComparingTo("45.00");
    }
}
```

## Workflow

1. Component layer: `@SpringBootTest(webEnvironment = RANDOM_PORT)`; connect a real client to `localhost` and the random port. Nothing leaves the machine.
2. STOMP (`@MessageMapping`, `@SendTo`, `SimpMessagingTemplate`): `WebSocketStompClient` with `JacksonJsonMessageConverter` (Boot 4; `MappingJackson2MessageConverter` on Boot 3), subscribe first, then send; collect frames in a `BlockingQueue`.
3. Raw WebSocket (`WebSocketHandler`): `StandardWebSocketClient` (servlet) or `ReactorNettyWebSocketClient` (WebFlux).
4. SSE (`SseEmitter`, `Flux<ServerSentEvent<T>>`, `text/event-stream`): `WebTestClient` with `.returnResult(...)`
   and `StepVerifier` for a `Flux`, or `WebClient` with `bodyToFlux(ServerSentEvent.class)` against the random port.
5. Unit layer: test the handler's logic directly (the method behind `@MessageMapping`), without a connection.
6. Assert what the spec states: the payload, the destination, the order of messages, and that a client without the
   right role or subscription receives nothing (security skill).

## Rules

- Wait with `poll(timeout)` or Awaitility, never `Thread.sleep`; a missing message must fail within seconds.
- Subscribe before you trigger the message. The simple broker sends no RECEIPT for a SUBSCRIBE, so a receipt cannot
  tell you the subscription is active: resend an idempotent request until the first frame arrives (as above). A
  request that must be sent exactly once is tested at the unit layer, on the method behind `@MessageMapping`.
- Close sessions and clients in the test (`try`-with-resources or `@AfterEach`).
- Connect to `localhost` only: never to SIT, UAT or production endpoints.

## References

- `references/sse.md`
