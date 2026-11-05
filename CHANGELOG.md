# Changelog

All notable changes to gta. Versions follow SemVer; the version lives in the root `pom.xml`.

## [Unreleased]

### Added
- Services on Java 8 and 11: `gta-junit` is Java 8 bytecode, briefs name the service's level and `javax.*` for Spring Boot 2, `build.javaHomeEnv` runs Maven with the JDK an old build needs, and `gtaw doctor` warns (instead of failing) for Java 8 to 16.

### Changed
- Roadmap (plan §15): every mainstream Spring service type is in scope, ordered by how common it is. Multi-module, messaging and scheduled jobs move to P1; security and caching join P2; WebFlux, Gradle, gRPC and GraphQL are P3; WebSocket/SSE joins P4.
- Briefs ask for tests at the service's own Java level (from `maven.compiler.release`, `java.version`, …) instead of "Java 17+", so a Java 17 service gets no newer syntax or APIs.
- `gtaw doctor` fails a service on a Java older than 17 (`service-java`); CI also builds on JDK 25.
- `docs/guides/quickstart.zh-CN.md`: a Chinese copy of the quickstart.

## [0.1.0] — 2026-11-05 (P0 preview: REST APIs only)

The first release. Other capabilities are explain-only until their phase (see the capability registry). Findings from the P0a platform validation go into 0.1.1.

### Fixed (code review, round 3; see docs/reviews/round-3-plan.md §6.3)
- Git failures (dubious ownership, a stale `index.lock`, an unknown base) fail closed instead of reading as "nothing changed"; git always runs with `LC_ALL=C`.
- A failed item commit unstages its files and undoes gtaw's own shard write.
- Parameterized and repeated tests keep their scenario tags; unreadable Surefire reports fail the run.
- Timed-out processes keep their output; stale reports that cannot be deleted fail the run.
- An out-of-scope file that was only staged is removed when it is restored.
- Oracle shard keys are validated; error codes from older state files no longer crash.
- The wrapper never puts Nexus credentials on a command line, and reports download failures as JSON.

### Fixed (round 4; see docs/reviews/round-4-plan.md)
- `init` and `upgrade` refuse an `AGENTS.md` whose gta block is damaged (a lone, reversed or duplicated marker) instead of replacing the text between mismatched markers.

### Added (round 3)
- Test case ledger per flow (`.gta/specs/<domain>/<flow>.cases.md`) and `.gta/specs/INDEX.md`, maintained row by row by gtaw; `gtaw cases [--rebuild]`, `gtaw cases confirm-retired`, `gtaw impact --base`.
- Session reports are tracked in `.gta/reports/<date>-<session>.md` and committed with the ledger changes they list.
- Scenario `priority=P0|P1|P2`; a `naming-conflict` decision when the test class gta would write belongs to a developer.
- A spec submit notes scenarios without `priority=` (they count as P1); `gtaw doctor` warns that multi-module services are not supported yet.
- `docs/guides/releasing.md`; tests for every `gtaw doctor` decision and for the oracle lock cases a mutation run found untested.
- `docs/guides/test-naming.md`, `docs/guides/diagram-style.md`, the artifact-map and package-layer diagrams.

### Changed (round 3)
- Generated tests are named after the source class they test (`OrderControllerIT`, `OrderControllerSliceTest`, seed `OrderApplicationIT`), fixtures live in `src/test/resources/<package>/<TestClass>/`.
- `ItemWorkflow` is split into `ItemWorkflow`, `Triage`, `ItemCommitter` and `PlanSync`; `Lock` and `Baseline` leave `WorkState`; renames `TestCase`→`TestOutcome`, `Briefs`→`BriefWriter`, `Proc`→`ProcessRunner`, `SurefireReports`→`SurefireReportParser`, `Registry`→`CapabilityRegistry`.
- Doctor checks for Oracle and Pub/Sub run only for services that use them; managed files are protected by the scope check; a flow without an entry point is refused instead of being briefed as a REST API.
- Spec diagrams: `%%` comments are allowed before the type; theme overrides and other styling than the `focus`/`external` classes are rejected.
- `docs/` is split into `guides/`, `design/` and `reviews/`.

### Added (engineering baseline, round 3)
- Maven wrapper, enforcer, Spotless (palantir-java-format), SpotBugs, JaCoCo with a 90 % line gate on gta-core, CONTRIBUTING, SECURITY, a PR template and Dependabot; CI builds on JDK 17 and 21.
- Every toolkit class has a mirrored `<Name>Test` (`MirroredTestNamesTest`); toolkit unit tests grew from 80 to about 300.
- `qualityGates.stabilityTimezone` / `stabilityLocale` configure the stability variant run.

### Changed (documentation)
- Documentation, agents, skills, templates and gtaw prompts are English. Chinese replies (`批准`, `其余默认`, `= 代码现状`) are still accepted.
- `README.md` and `docs/design/plan.md` are English, with `README.zh-CN.md` and `docs/design/plan.zh-CN.md` copies and a language switch.
- The explanatory diagrams (architecture, loop, item states, spec lifecycle) are rendered by `scripts/diagrams/render.py` into light and dark SVGs in the style of cathrynlavery/diagram-design; CI checks they are current.
- A repository test keeps Chinese out of every file except the `*.zh-CN.md` copies, the alias parser and tests.

### Fixed (code review, round 2; see docs/reviews/round-2-remediation.md)
- Only an exact "approve <flow>@<rev>" (or its Chinese alias) approves. Negated or conditional replies become change requests.
- A spec edited after it was shown for approval is not approved; it returns as a new revision.
- Timed-out test runs count against the item's budgets and reach a human.
- A clean session end no longer resets items waiting for triage. Takeover resets only items being edited.
- The scope check trusts only commits recorded in gtaw's commit ledger, so a forged `Gta-*` trailer does not count.
- Unreadable oracle shards fail closed, both in commits and in the gate.
- `relock` re-derives tokens from the spec and holds the work lock.
- Review verdicts need the review node's token.
- The spec loop is bounded (`spec.maxSubmitAttempts`, questions per round, clarify rounds).
- Retry resets the check budget.
- Wrappers refuse to run an unpinned jar.
- `abort` keeps `.gta/work/recover/`.
- `upgrade` removes or reports managed files the new version no longer ships.
- `.gta/config.json` rejects unknown keys.
- `oracleReview` is an organisation floor.

### Fixed (code review, round 1)
- A reviewer `reject` no longer fails with an illegal transition.
- Items adopted after a rebuild get a token, so they can be repaired instead of deadlocking.
- The scope check sees commits made outside gtaw and the source of a rename. Such commits go to a human.
- Corrupt working-state files are kept and reported (`STATE_CORRUPT`) instead of being silently reset.
- A second relock by the same person is accepted by the gate. Relocks now carry `relockedAt`.
- `init`/`upgrade` read JSONC, never rewrite files that contain comments, and write nothing if an input cannot be parsed.
- An `@KnownDefect` added without a human's confirmation is refused. Adopted pins stay `defect-found`.
- Per-run temporary gcloud directories are deleted.

### Changed
- `Engine` is split into `SpecWorkflow`, `ItemWorkflow`, `SessionManager`, `SessionEnd`, `HumanPrompts` and `EscalationWriter` behind the same facade.
- `AtomicFiles` and `Yaml` are split out of `Json`. Gate concern codes and new environment and state codes are in `ErrorCode`.
- `--format` is `json|human`.
- `plan.md` moved to `docs/design/` and `tools/` to `scripts/`.

### Added
- `gtaw` CLI (`gta-cli`):
  - commands: `init`, `upgrade`, `doctor`, `next`, `check`, `submit`, `answer` (with `--file`), `status`, `abort`, `run`, `verify`, `gate` (and `gate waive`), `relock`, `report`;
  - stubs for `mutate` (P2) and `env` (P4);
  - `gtaw` / `gtaw.cmd` wrappers that download the pinned jar from Nexus and verify its sha256.
- Spec-first loop (`gta-core`):
  - OpenSpec-compatible specs with EARS requirements;
  - question rounds and approvals recorded with the human's words;
  - planning, briefs;
  - monotonic oracle lock: request inputs are watched, expected values are locked;
  - change check with revert;
  - failure triage by cause chain;
  - known-defect pinning, stability runs;
  - a base-anchored gate and a traceability report.
- `gta-junit`: `@KnownDefect` (strict expected failure with a fingerprint) and the scenario results listener.
- Harness content:
  - 4 agents, the `/gta` command, guardrails, 17 skills;
  - a capability registry (REST APIs active in P0; other capabilities explain-only until their phase).
- `gta-harness-gen`: lints `core/`; generates `copilot/` (VS Code and IntelliJ Copilot) and `opencode/`; `--check` for CI.
- Evals:
  - `order-service` fixture (Spring Boot 4.1) with seeded defects;
  - a scripted end-to-end run covering regression detection and the known-defect lifecycle.
