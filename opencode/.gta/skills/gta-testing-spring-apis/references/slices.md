# Slice tests

```java
@WebMvcTest(OrderController.class)
@Tag("slice") @Tag("flow:order.create") @Tag("gta")
class OrderCreateTest {
    @Autowired MockMvcTester mvc;
    @MockitoBean OrderService service;

    @Test
    @Tag("scenario:order.create/s01")
    @DisplayName("[order.create/s01] missing amount is rejected")
    void shouldReturn400WhenAmountMissing() {
        var result = mvc.post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"EUR\"}").exchange();
        assertThat(result).hasStatus(400)
                .bodyJson().extractingPath("$.title").isEqualTo("Bad Request");
        verifyNoInteractions(service);
    }
}
```

- Boot 4: `@WebMvcTest` lives in `org.springframework.boot.webmvc.test.autoconfigure`; Boot 3.5: `org.springframework.boot.test.autoconfigure.web.servlet`.
- WebFlux: `@WebFluxTest` + `WebTestClient`.
- Serialisation only: `@JsonTest` + `JacksonTester`.
- Security: `@WithMockUser(roles = "ADMIN")` or `.with(jwt().authorities(...))` from spring-security-test.
