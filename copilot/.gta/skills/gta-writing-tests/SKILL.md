---
name: gta-writing-tests
description: House rules for every gta test in a Maven Spring Boot 4.1 / 3.5 service - JUnit 6 or 5 following the Spring Boot parent, naming, tags, @DisplayName, test data, gta-junit, test-scope dependencies, and what to do when production code has no test seam. Use when writing or fixing any test from a gta author brief.
---

# Writing gta tests

## Quick start

```java
@Tag("component") @Tag("gta")
class OrderControllerIT {                  // tests of com.acme.order.api.OrderController (the flow's entry point)

    @Test
    @Tag("flow:order.create") @Tag("scenario:order.create/s03")
    @DisplayName("[order.create/s03] gateway silent for 3 seconds")
    void shouldRespond503WhenGatewayTimesOut() {
        // arrange: inputs that produce the WHEN
        // act: call the entry point
        // assert: every THEN value from the brief
    }
}
```

## Workflow

1. Put the test in the class and package the brief names; reuse support classes from sibling tests.
2. Tags: the layer (`unit`, `slice`, `component`, `contract`) and `gta` on the class; `flow:<flowId>` and `scenario:<scenarioId>` on each method (several flows of one source class share its test class); add `characterization` when the brief says the intent is characterization.
3. Naming: the brief names the class: `<SourceClass><Suffix>` in the source's package (`references/naming.md`). Methods `should<Outcome>When<Condition>()`; `@DisplayName("[<scenarioId>] …")`.
4. Test data: builders or Instancio with a fixed seed (`references/test-data.md`); fixtures in the test class's folder `src/test/resources/<package path>/<TestClass>/`.
5. Use `./gtaw check` until it compiles and runs, then `./gtaw submit`.

## Rules

- JUnit version follows the Spring Boot parent: Boot 4.x → JUnit 6, Boot 3.5 → JUnit 5. The `org.junit.jupiter.api` imports are the same. Differences that matter: `references/boot3-vs-boot4.md`.
- One test class per source class and kind: `Test` unit, `SliceTest` Spring slice, `IT` component, `ContractIT` contract (`references/naming.md`). gtaw runs them through Surefire with an explicit `-Dtest`.
- Support code never ends in `Test` or `IT`: `<Port>Fake`, `<Type>TestData`, `<Downstream>Stub`, `<Name>TestConfig`.
- Never change production code. If there is no seam (no injectable `Clock`, logic inside a framework callback), use the seams in `references/seams.md` and mention "refactor suggested" in your result.
- Only the foundation item may change `pom.xml`: test-scope dependencies only, versions managed by the Boot parent left out (`references/build-files.md`).
- `@KnownDefect` is added only when the brief says a human confirmed a defect (`references/gta-junit.md`).

## References

- `references/naming.md` · `references/junit.md` · `references/test-data.md` · `references/gta-junit.md`
- `references/boot3-vs-boot4.md` · `references/build-files.md` · `references/seams.md`
