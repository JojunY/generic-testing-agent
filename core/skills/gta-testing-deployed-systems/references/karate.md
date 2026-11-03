# Karate

- Karate 2.x with the JUnit module matching the service's JUnit (Boot 4.1 → `karate-junit6`).
- Runner `<basePackage>.system.KarateSystemSuite` (not `*IT`, so Failsafe does not pick it up).
- Tags: `@gta @system @flow=<flowId> @scenario=<scenarioId>`; `@known-defect` for pinned defects.
- Fuzzy matchers (`#uuid`, `#notnull`, `#regex`) for volatile fields only; expected business values stay exact.
- Karate 2.1+: Java interop is off by default in mock servers.
