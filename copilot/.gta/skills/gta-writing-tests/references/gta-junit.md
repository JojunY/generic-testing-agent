# gta-junit

Test-scope dependency (from Nexus) that every gta service adds once (the foundation item does it):

```xml
<dependency>
  <groupId>com.gta</groupId>
  <artifactId>gta-junit</artifactId>
  <version>${gta.version}</version>
  <scope>test</scope>
</dependency>
```

It provides:

1. **GtaResultsListener** — registered automatically; writes `target/gta-results/*.jsonl` with each test's class, method, tags and outcome so gtaw can map results to scenarios.
2. **`@KnownDefect(id, failure)`** — pins a human-confirmed product defect (strict xfail):

```java
@Test
@Tag("scenario:order.read/s07")
@KnownDefect(id = "order.read/f01", failure = "3f9c0a1b2c4d")   // values come from the brief
void shouldRejectReadWhenNotOwner() { ... }
```

- same failure fingerprint → aborted, build stays green
- test passes → fails with "defect looks fixed, remove @KnownDefect"
- fails differently → fails

Never add `@KnownDefect` on your own; only when the brief says a human confirmed the defect.
