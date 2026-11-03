---
name: gta-stubbing-with-wiremock
description: Replaces downstream HTTP services (GCP and non-GCP) with in-process WireMock stubs in gta component tests, including timeouts, 5xx, malformed bodies, connection resets and verification of requests sent. Use when a scenario depends on an HTTP call to another service.
---

# Stubbing HTTP dependencies with WireMock

## Quick start

```java
@RegisterExtension
static WireMockExtension gateway = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort()).build();

@DynamicPropertySource
static void props(DynamicPropertyRegistry r) {
    r.add("payment.gateway.url", gateway::baseUrl);
}

@Test
void shouldRespond503WhenGatewayTimesOut() {
    gateway.stubFor(post(urlEqualTo("/authorize")).willReturn(ok().withFixedDelay(3_500)));
    // act + assert
    gateway.verify(1, postRequestedFor(urlEqualTo("/authorize")));
}
```

## Workflow

1. One WireMock server per downstream; inject its URL through `@DynamicPropertySource`.
2. Stub only what the scenario needs; keep stubs in the test or under `src/test/resources/fixtures/<domain>/<flow-name>/`.
3. Fault scenarios: `references/fault-injection.md`.
4. Verify the requests the service sent (method, path, key headers, body fields) when the spec talks about them.

## Rules

- Stubs and fixtures are test inputs: changing them after an assertion failure is routed to the reviewer.
- Never point tests at a real downstream; for third-party systems see `references/external-services.md`.
- Dependency: `org.wiremock:wiremock-standalone` (test scope) through Nexus.

## References

- `references/fault-injection.md` · `references/external-services.md`
