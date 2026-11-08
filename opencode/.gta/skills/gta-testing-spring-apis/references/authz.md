# Authorisation tests

From the spec's `## Access` table generate:

- a positive test per row (role × operation),
- a negative test per ownership constraint: other user, other tenant, anonymous,
- the "exists but not yours" status code exactly as the spec says (`403` or `404`).

```java
@Test
@Tag("scenario:borrow.read/s07")
@DisplayName("[borrow.read/s07] a client cannot read another client's borrow")
void shouldReturn404WhenReadingAnotherClientsBorrow() {
    var otherBorrow = borrows.save(aBorrow().owner("bob").build());
    var result = mvc.get().uri("/api/borrows/{id}", otherBorrow.getId())
            .with(jwt().jwt(j -> j.subject("alice"))).exchange();
    assertThat(result).hasStatus(404);
}
```

A negative test that passes only because of a missing fixture is worthless: assert the resource exists for its owner in the same test or a sibling test.
