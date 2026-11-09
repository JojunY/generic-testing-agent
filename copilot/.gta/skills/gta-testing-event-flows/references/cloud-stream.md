# Spring Cloud Stream functions

A `@Bean Function`, `Consumer` or `Supplier` in a Cloud Stream service is bound to destinations: `recall` reads
`recall-in-0` and writes `recall-out-0`, whose destinations (topics) come from
`spring.cloud.stream.bindings.<binding>.destination`. Test it with the test binder, which replaces Kafka, Rabbit or
Pub/Sub inside the JVM (checked on Spring Boot 4.1 with Spring Cloud Stream 5):

```java
@SpringBootTest
@Import(TestChannelBinderConfiguration.class)
class RecallFunctionsIT {
    @Autowired InputDestination input;
    @Autowired OutputDestination output;

    @Test
    @Tag("flow:recall.recall") @Tag("scenario:recall.recall/s01")
    @DisplayName("[recall.recall/s01] a partial recall returns the recalled shares")
    void shouldReturnTheRecalledSharesWhenRecallIsPartial() {
        input.send(MessageBuilder.withPayload(
                        "{\"borrowId\":\"b-1\",\"borrowedQuantity\":1000,\"recalledQuantity\":400}".getBytes(UTF_8))
                .build(), "recall-notices");

        Message<byte[]> sent = output.receive(2000, "return-instructions");
        assertThat(sent).isNotNull();
        String json = new String(sent.getPayload(), UTF_8);
        assertThat(JsonPath.<Number>read(json, "$.quantity").longValue()).isEqualTo(400);
    }
}
```

- Dependency: `org.springframework.cloud:spring-cloud-stream-test-binder` (test scope).
- With a real binder on the classpath too (the Pub/Sub or Kafka binder), set `spring.cloud.stream.default-binder=integration`
  and switch the broker's client off for tests (`spring.cloud.gcp.pubsub.enabled=false`) in
  `src/test/resources/config/application.properties`.
- Send and receive by destination name (the topic), not the binding name.
- `output.clear()` before a test that asserts nothing is sent; `output.receive(200, ...)` returning `null` proves it.
- Duplicates and poison messages: send the same message twice, or a malformed payload, and assert the end state and the
  error channel (`<destination>.<group>.errors`).
