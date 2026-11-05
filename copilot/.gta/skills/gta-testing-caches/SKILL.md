---
name: gta-testing-caches
description: Tests Spring Cache behaviour (@Cacheable, @CachePut, @CacheEvict) with Caffeine or Redis - that a repeated call is served from the cache, that writes evict or refresh the entry, that keys separate tenants and arguments, that TTLs expire, and that a stale value is never returned after an update. Use when a gta author brief lists the caching capability for a flow (P2).
---

# Testing caches

## Quick start

Count calls to the source behind the cache; the cached answer must equal the source's answer.

```java
@Test
@Tag("scenario:catalog.get-price/s02")
@DisplayName("[catalog.get-price/s02] a price change is visible on the next read")
void shouldReturnTheNewPriceAfterAnUpdate() {
    prices.get("sku-1");                       // fills the cache
    prices.update("sku-1", new BigDecimal("12.50"));

    assertThat(prices.get("sku-1")).isEqualByComparingTo("12.50");
    verify(priceSource, times(2)).load("sku-1");
}
```

## Workflow

1. Hit: call twice with the same arguments; the source (a `@MockitoSpyBean`, a WireMock stub or a counting fake) is called once and both answers are equal.
2. Key: call with arguments that must not share an entry (other id, other tenant, other locale); each reaches the source.
3. Eviction: after the write path (`@CacheEvict`/`@CachePut`), the next read returns the new value. This is the scenario that finds stale-data defects.
4. Expiry: a TTL is asserted with a controllable ticker (Caffeine `Ticker`) or a short TTL property and Awaitility; never `Thread.sleep`.
5. Store: Caffeine runs in-process; for Redis use an embedded or fake server when one exists in the service's tests, otherwise test against `ConcurrentMapCacheManager` and note the lower fidelity (`references/cache-stores.md`).
6. Clear the caches between tests (`CacheManager#getCacheNames` → `clear()`) so order does not matter.

## Rules

- The expected value is the source's value from the spec, never "whatever the cache returned first".
- A cache test always names the source call count; a test that only reads twice proves nothing.

## References

- `references/cache-stores.md`
