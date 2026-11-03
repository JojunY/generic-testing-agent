# Authorisation tests

From the spec's `## Access` table generate:

- a positive test per row (role × operation),
- a negative test per ownership constraint: other user, other tenant, anonymous,
- the "exists but not yours" status code exactly as the spec says (`403` or `404`).

```java
@Test
@Tag("scenario:order.read/s07")
@DisplayName("[order.read/s07] customer cannot read another customer's order")
void shouldReturn404WhenReadingForeignOrder() {
    var otherOrder = orders.save(anOrder().owner("bob").build());
    var result = mvc.get().uri("/api/orders/{id}", otherOrder.getId())
            .with(jwt().jwt(j -> j.subject("alice"))).exchange();
    assertThat(result).hasStatus(404);
}
```

A negative test that passes only because of a missing fixture is worthless: assert the resource exists for its owner in the same test or a sibling test.
