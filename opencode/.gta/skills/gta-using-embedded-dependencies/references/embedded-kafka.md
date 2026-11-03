# Embedded Kafka

```java
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"order-created"}, kraft = true)
@TestPropertySource(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
class OrderEventsIT { ... }
```

- Dependency: `org.springframework.kafka:spring-kafka-test` (version from the Boot parent).
- Consume with a test consumer from `KafkaTestUtils.consumerProps(...)` and `KafkaTestUtils.getSingleRecord(...)`, or wait with Awaitility on the side effect.
- Windows: set `log.dirs` to a fresh directory under `target/` (`@EmbeddedKafka(brokerProperties = "log.dirs=target/embedded-kafka")`) to avoid file locks between runs.
- Test duplicate delivery by sending the same record twice and asserting one effect.
