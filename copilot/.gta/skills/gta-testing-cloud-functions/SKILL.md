---
name: gta-testing-cloud-functions
description: Tests Spring Cloud Function beans locally, including functions deployed as Cloud Run functions (spring-cloud-function-adapter-gcp) - the bean itself at the unit layer, the FunctionCatalog lookup and the HTTP endpoint of spring-cloud-function-web at the component layer, Message inputs for event-triggered functions, never a deployed function. Use when a gta author brief targets a cloud-function scenario (P4).
---

# Testing cloud functions

## Quick start

```java
@SpringBootTest
@AutoConfigureMockMvc
class QuoteFunctionApplicationIT {
    @Autowired FunctionCatalog functions;
    @Autowired MockMvcTester mvc;

    @Test
    @Tag("flow:quote.quote") @Tag("scenario:quote.quote/s01")
    @DisplayName("[quote.quote/s01] the daily fee is quoted on Act/360")
    void shouldQuoteTheDailyFeeWhenTheRateIsSet() {
        Function<QuoteRequest, Quote> quote = functions.lookup("quote");

        assertThat(quote.apply(new QuoteRequest("ACME", 10_000, 25)).dailyFee()).isEqualByComparingTo("3.47");
    }

    @Test
    @Tag("flow:quote.quote") @Tag("scenario:quote.quote/s02")
    @DisplayName("[quote.quote/s02] the HTTP endpoint answers with the quote")
    void shouldQuoteOverHttpWhenPosted() {
        assertThat(mvc.post().uri("/quote").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"security\":\"ACME\",\"quantity\":10000,\"rateBps\":25}"))
                .hasStatusOk()
                .bodyJson().extractingPath("$.dailyFee").isEqualTo(3.47);
    }
}
```

Checked on Spring Boot 4.1 with Spring Cloud 2025.1 (Spring Cloud Function 5).

## Workflow

1. The flow is the function bean (`@Bean Function`, `Consumer` or `Supplier`); its name is the bean name.
2. Unit layer: call the bean method's result directly (`new QuoteFunctions().quote().apply(request)`), no context.
3. Component layer: `@SpringBootTest` and `FunctionCatalog.lookup("<name>")`: the catalog applies the same type
   conversion the deployed function gets (JSON to the input record), so the test sees what Cloud Run sees.
4. With `spring-cloud-function-web` (or the starter) the function is also `POST /<name>`: test it with `MockMvcTester`
   as in Quick start, including the status and body of a bad input.
5. Event-triggered functions (a `Consumer<Message<T>>` behind Pub/Sub or a CloudEvent): build the input with
   `MessageBuilder.withPayload(...)` and the headers the trigger sets, then assert the side effect.
6. Several functions: `spring.cloud.function.definition` decides which one is exported; a composition (`a|b`) is a
   flow of its own, looked up with that definition (`references/function-definition.md`).

## Rules

- Never deploy, invoke or read a deployed function (`gcloud functions`, `gcloud run`, a `*.run.app` URL); everything
  runs in the test JVM.
- The adapter (`spring-cloud-function-adapter-gcp`) only wraps the bean: do not test the adapter, test the function.
- Assert the output value and its type, not only that the call returned.

## References

- `references/function-definition.md`
