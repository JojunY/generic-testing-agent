# Karate

- Karate 2.x with the JUnit module matching the service's JUnit (Boot 4.1 → `karate-junit6`).
- One runner per source class: `src/test/karate/java/<package path>/<SourceClass>Karate.java` (e.g. `OrderControllerKarate`). The `Karate` suffix matches neither Surefire nor Failsafe defaults, so it runs only in the `karate` profile (`build-helper-maven-plugin` adds `src/test/karate/java` and `src/test/karate/resources`).
- Features beside it: `src/test/karate/resources/<package path>/<SourceClass>Karate/<flow-name>.feature`.
- Tags: `@gta @system @flow=<flowId> @scenario=<scenarioId>`; `@known-defect` for pinned defects.
- Fuzzy matchers (`#uuid`, `#notnull`, `#regex`) for volatile fields only; expected business values stay exact.
- Karate 2.1+: Java interop is off by default in mock servers.
