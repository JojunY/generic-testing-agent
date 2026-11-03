# Pact

- Consumer test (JUnit 5/6): `@ExtendWith(PactConsumerTestExt.class)`, `@Pact(provider = "payment-gateway", consumer = "order-service")` defines interactions; the test calls the mock server.
- Provider verification: `@Provider("order-service")` + `@PactBroker` or `@PactFolder`, a `@TestTemplate` with `PactVerificationInvocationContextProvider`, and `@State("order exists")` methods that set up data.
- Run provider verification against the app started with embedded dependencies.
- Publish/verify results to the broker only from CI, never from developer machines.
