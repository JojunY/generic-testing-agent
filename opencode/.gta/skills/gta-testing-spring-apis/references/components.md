# Component tests

- `@SpringBootTest(webEnvironment = RANDOM_PORT)`; add `@AutoConfigureMockMvc` (Boot 4) to use `MockMvcTester`, or REST Assured against `@LocalServerPort`.
- Downstream HTTP: one WireMock server per downstream, started in a static extension (`@RegisterExtension static WireMockExtension gateway = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();`), URL injected with `@DynamicPropertySource`.
- Data: embedded or shared test database per the embedded-dependencies skill; read state back through repositories or `JdbcTemplate`, never through the service under test.
- Messages: capture with the embedded broker or a fake publisher bean and assert what was (and was not) published.
- Speed: share one Spring context per test class configuration (same properties ⇒ cached context).

```java
@DynamicPropertySource
static void props(DynamicPropertyRegistry r) {
    r.add("payment.gateway.url", gateway::baseUrl);
    r.add("payment.gateway.timeout", () -> "3s");
}
```
