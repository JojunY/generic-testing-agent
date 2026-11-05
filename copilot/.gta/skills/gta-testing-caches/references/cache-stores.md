# Cache stores in tests

| Production store | Test store | Fidelity |
|---|---|---|
| Caffeine | the same Caffeine configuration (`spring.cache.caffeine.spec`) | high |
| Redis (`spring-boot-starter-data-redis`) | an embedded Redis the service's tests already use, otherwise `spring.cache.type=simple` | medium with embedded, low with `simple`: no TTL, no serialisation |
| Hazelcast / Infinispan | their embedded single-node mode | high |

With `spring.cache.type=simple` serialisation is never exercised: a value that is not `Serializable` (or not readable by
the Redis serializer) fails only in production. Say so in the result and keep one test that round-trips the value
through the production serializer when the service configures one.
