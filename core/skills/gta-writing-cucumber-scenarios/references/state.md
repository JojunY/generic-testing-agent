# Scenario state

- **Glue instances**: cucumber-spring creates the step classes per scenario (glue scope), so fields such as the last
  response start empty in every scenario. `@Autowired` beans come from the one Spring context.
- **Shared, reset in `@Before`**: WireMock servers (`resetAll()`), circuit breakers and retries (`reset()` on the
  registry entry), queue channels (`clear()`), the Cloud Stream test binder's output (`OutputDestination.clear()`),
  repositories the scenario writes to (delete what it created, or use unique ids per scenario).
- **Order**: hooks run in `order` (lowest first); the gta pin hook uses `order = 0`, so your resets use 10 or more.
- **One context**: there is exactly one `@CucumberContextConfiguration` class in the glue. Test properties the
  scenarios need go there or in `src/test/resources/config/application.properties`, never in a second configuration.
