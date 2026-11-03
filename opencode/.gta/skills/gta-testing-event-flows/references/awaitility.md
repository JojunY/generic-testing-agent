# Awaitility

```java
await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
       .untilAsserted(() -> assertThat(repo.findById(id)).get().extracting(Order::getStatus).isEqualTo(PAID));
```

- Always set `atMost`; timeouts are classified as `TEST_TIMEOUT` and triaged.
- Wait for the observable effect, not for an internal flag.
- For "nothing happens" assertions use `during(Duration.ofSeconds(2))` with `atMost` slightly larger.
- Timing literals are not part of the locked oracle: adjusting them is allowed when fixing flakiness.
