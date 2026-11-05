---
name: gta-testing-spring-security
description: Tests the access rules of Spring Security services - the authenticated, forbidden and anonymous paths of each endpoint, method security (@PreAuthorize, @Secured), OAuth2 resource servers with JWT scopes and claims, CSRF and ownership checks - with MockMvc request post-processors instead of real tokens. Use when a gta author brief lists the security capability for a flow (P2).
---

# Testing Spring Security

## Quick start

The spec's Access table is the oracle: one scenario per row that grants, and one per role that is refused.

```java
@Test
@Tag("scenario:order.cancel-order/s03")
@DisplayName("[order.cancel-order/s03] another customer cannot cancel the order")
void shouldRefuseToCancelAnotherCustomersOrder() throws Exception {
    mvc.perform(post("/api/orders/{id}/cancel", ORDER_OF_C1)
                    .with(jwt().jwt(j -> j.subject("c-2")).authorities(new SimpleGrantedAuthority("SCOPE_orders.write"))))
            .andExpect(status().isForbidden());
}
```

## Workflow

1. Read the Access table of the spec. Every role × operation row becomes a scenario; a missing row is an open question, never a guess.
2. Authenticate with request post-processors (`jwt()`, `oauth2Login()`, `user()`, `httpBasic()`) or `@WithMockUser`; never call a real identity provider (`references/test-identities.md`).
3. For each protected endpoint assert three outcomes: allowed (2xx and the business effect), forbidden (`403`, nothing changed) and anonymous (`401`, or the login redirect for browser flows).
4. Method security: call the bean through the Spring context (not `new`) so the proxy applies, and assert `AccessDeniedException` for a refused caller.
5. Ownership: one scenario where the caller owns the resource and one where another caller of the same role does not; this is where broken object-level authorization hides.
6. CSRF: state-changing browser endpoints refuse a request without `.with(csrf())`; stateless JWT APIs usually disable CSRF, assert whichever the configuration says.

## Rules

- Expected status codes and effects come from the spec's Access table; a test never weakens a rule to make it pass.
- Assert that a refused request changed nothing (the repository or the downstream stub received no call).
- No hard-coded real tokens, keys or passwords in tests; build identities with the post-processors.

## References

- `references/test-identities.md`
