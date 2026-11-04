---
name: gta-testing-spring-apis
description: Tests Spring MVC, WebFlux, gRPC and GraphQL endpoints at the slice level (MockMvcTester, @WebMvcTest, @JsonTest, access matrix) and component level (@SpringBootTest with embedded dependencies, WireMock and REST Assured), including authorisation negatives and transaction-visible effects. Use when a gta author brief targets a rest-api, grpc-api or graphql-api scenario.
---

# Testing Spring APIs

## Quick start (component, Boot 4.1)

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("component") @Tag("gta")
class OrderControllerIT {
    @Autowired MockMvcTester mvc;
    @Autowired OrderRepository orders;

    @Test
    @Tag("flow:order.create") @Tag("scenario:order.create/s03")
    @DisplayName("[order.create/s03] gateway silent for 3 seconds")
    void shouldRespond503WhenGatewayTimesOut() {
        gateway.stubFor(post("/authorize").willReturn(ok().withFixedDelay(3_500)));   // WireMock, see stubbing skill

        var result = mvc.post().uri("/api/orders").contentType(APPLICATION_JSON)
                .content(fixture("s03.request.json")).exchange();

        assertThat(result).hasStatus(503);
        assertThat(orders.findAll()).singleElement()
                .extracting(Order::getStatus).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }
}
```

## Workflow

1. Pick the layer from the brief: `slice` for validation, serialisation, error mapping and the access matrix; `component` for anything involving persistence, downstream calls, transactions or messages.
2. Slice: `@WebMvcTest(OrderController.class)` + `MockMvcTester` + `@MockitoBean` for services (`references/slices.md`).
3. Component: real Spring context, embedded dependencies (`gta-using-embedded-dependencies`), WireMock for every HTTP downstream, REST Assured or `MockMvcTester` against the running app (`references/components.md`).
4. Access matrix: one positive and one negative test per ownership constraint (`references/authz.md`).
5. gRPC / GraphQL: `references/grpc.md`, `references/graphql.md`.

## Rules

- Component tests do not use test-managed `@Transactional`: rollback hides AFTER_COMMIT listeners, outbox publishing, flush-time constraint errors and lazy loading. Clean up in `@AfterEach`.
- Assert ProblemDetail fields (`status`, `type`, `title`) when the spec names them.
- Use `@DynamicPropertySource` to point the app at WireMock and embedded dependencies.
- No containers, no real cloud: credentials are cut off by gtaw anyway.

## References

- `references/slices.md` · `references/components.md` · `references/authz.md` · `references/grpc.md` · `references/graphql.md`
