---
name: gta-writing-cucumber-scenarios
description: Writes gta scenarios as Cucumber features in a service that already runs Cucumber - the feature file and step definitions class the brief names, the scenario, intent and priority tags, Then steps that carry the expected values the oracle lock reads, reused glue, per-scenario state reset in hooks, and pins as @known-defect tags. Use when a gta author brief names a feature file (the service has a Cucumber suite) (P4).
---

# Writing Cucumber scenarios

## Quick start

`src/test/resources/features/borrow/book.feature`:

```gherkin
@gta @component @flow:borrow.book
Feature: Book a stock borrow

  @scenario:borrow.book/s02 @hardening @p0
  Scenario: [borrow.book/s02] an unreachable inventory leaves the borrow pending a locate
    Given the inventory service is unreachable
    When client "c-1" books a borrow of 10000 shares of "ACME"
    Then the response status is 503
    And the borrow status is "PENDING_LOCATE"
```

`src/test/java/com/example/lending/borrow/BorrowControllerSteps.java`:

```java
public class BorrowControllerSteps {
    @Autowired MockMvcTester mvc;
    @Autowired CircuitBreakerRegistry breakers;
    MvcTestResult response;

    @Before
    public void reset() {
        CucumberSpringConfiguration.INVENTORY.resetAll(); // shared WireMock server of the team's configuration
        breakers.circuitBreaker("inventory").reset();
    }

    @Given("the inventory service is unreachable")
    public void inventoryUnreachable() {
        CucumberSpringConfiguration.INVENTORY.stubFor(
                post("/reservations").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    }

    @When("client {string} books a borrow of {long} shares of {string}")
    public void book(String client, long quantity, String security) {
        response = mvc.post().uri("/api/borrows").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"%s\",\"security\":\"%s\",\"quantity\":%d}".formatted(client, security, quantity))
                .exchange();
    }

    @Then("the response status is {int}")
    public void status(int expected) {
        assertThat(response).hasStatus(expected);
    }

    @Then("the borrow status is {string}")
    public void borrowStatus(String expected) {
        assertThat(response).bodyJson().extractingPath("$.status").isEqualTo(expected);
    }
}
```

Checked on Spring Boot 4.1 with Cucumber 8 (JUnit 6); services on Boot 3.x use Cucumber 7 (JUnit 5).

## Workflow

1. The brief names the feature file, the steps class and the suite that runs them (the team's `@Suite
   @IncludeEngines("cucumber")` class). Write only those two files; the suite and the `@CucumberContextConfiguration`
   class belong to the team.
2. One feature file per flow; one `Scenario` per gta scenario, named `[<scenarioId>] <title>` and tagged
   `@scenario:<id> @<intent> @<priority>`. The feature carries `@gta @<layer> @flow:<flowId>`. gtaw corrects these tags
   before every run.
3. Given/When steps are the inputs; Then steps are the assertions. Write every expected value of the brief's oracle
   literally in a Then step (`Then the response status is 503`), and make the step definition assert its parameter.
   gtaw locks those Then steps once the scenario reached the code: you may add Then steps, never change or remove
   locked ones.
4. Reuse the glue's existing steps when their wording fits: Cucumber refuses two definitions that match one step. New
   steps go in the brief's steps class.
5. State: glue objects live for one scenario, but the Spring context, embedded brokers, WireMock servers and resilience
   registries are shared. Reset what you use in a `@Before` hook (`references/state.md`).
6. A scenario outline's examples are part of the oracle: put the expected values in their own column.

## Rules

- Never tag a scenario to skip it (`@ignore`, `@wip`, `@disabled`) and never filter tags in the suite: gtaw runs every
  scenario by its `@scenario:` tag.
- Pin a confirmed defect only when the brief says so, by adding `@known-defect @known-defect:<fingerprint>` to the
  scenario; the glue's hook (`KnownDefects.abortPinnedOutsideGta`) keeps a plain build green and gtaw checks the pin.
- No `Thread.sleep`; wait with Awaitility or a receive timeout inside the Then step.
- English Gherkin keywords only (`Given`, `When`, `Then`, `And`, `But`).

## References

- `references/state.md`
