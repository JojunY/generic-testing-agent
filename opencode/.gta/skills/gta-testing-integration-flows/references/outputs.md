# Reading what a flow publishes

- **Queue channel** (`QueueChannel` bean, `.channel(c -> c.queue("name"))`): `receive(timeout)` in the test, as in the
  skill's Quick start.
- **Direct or publish-subscribe channel**: subscribe a handler in the test that collects messages into a
  `BlockingQueue`, and unsubscribe it in `@AfterEach`; the flow's own subscribers keep running, so assert their side
  effects too.
- **Outbound adapter** (JMS, HTTP, Pub/Sub, file): assert at the dependency: the embedded broker's queue, a WireMock
  stub (`gta-stubbing-with-wiremock`), a temporary folder. `spring-integration-test` also lets a test replace a
  handler (`MockIntegrationContext.substituteMessageHandlerFor`, `MockIntegration.mockMessageHandler()`); use it when
  the dependency cannot run locally, and say so in the item (fidelity medium).
- **Error channel**: a step that throws sends an `ErrorMessage` to `errorChannel` (or the flow's own error channel);
  read it like any other channel and assert the cause.
