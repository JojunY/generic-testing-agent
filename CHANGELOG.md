# Changelog

All notable changes to gta. Versions follow SemVer; the version lives in the root `pom.xml`.

## [Unreleased]

### Fixed
- `OK, but change s02` on an approval prompt approved the spec, and on a decision took the recommended option: only a bare `OK` takes a recommendation now, anything more is a change request (approval) or is refused (decision).
- Gradle's JUnit XML names testcases by display name and puts the exception type in front of the message; the report parser now maps such cases to gta-junit's results and fingerprints the bare message, so `@KnownDefect(failure=...)` matches.
- A THEN value of `true` or `false` could never be satisfied: the oracle scanner ignored boolean literals. `isEqualTo(true)`, `isTrue()`, `isFalse()`, `assertTrue` and `assertFalse` now assert them (found by the Spring Batch eval).

### Added
- Authors ask instead of guessing (plan §5.10, P4): `gtaw submit --item <id> --token <t> --question "<q>" [--recommend "<a>"]` shows the question to the person as an answer node (in the status block, with the recommendation), spends the brief's token and keeps the item with its author. The reply is the answer in the person's own words, or `OK` for the recommendation; the author's next brief lists it under "Answers from the person". At most 3 questions per item.
- `/gta note <text>` (`gtaw note`, plan §5.10, P4): a person tells gta what it cannot read from the code. gtaw appends the note with date and author to the tracked `.gta/notes.md` and commits it; analyst briefs list the notes, which a scenario cites as `source=documented doc=.gta/notes.md`.
- The status block (plan §5.10, P4): every node that reaches a person (`answer`, `escalate`, `done`) carries `display`, one block in five fixed parts: state and progress (spec state, tests passed/total, defects found), what happened, what was checked, what needs you, and what comes next. gtaw writes it from facts it recorded along the way (spec written or approved, answers recorded, tests passed or failed, re-runs, the reviewer's verdicts, pinned defects, the gate) and shows each fact once, in `output.language`. When no flow is selected it lists the flows with a recommended one (`recommendedFlow`, taken by `OK`). The orchestrator shows the block verbatim and no longer writes its own summaries.
- Questions with a recommended answer (plan §5.10, P4): every open question has exactly one option marked `(recommended: <why>)`; the linter refuses a question without one, and the analyst brief asks for it. Replies: `OK` takes every recommendation (recorded word for word, as `<letter>(recommended)`), `OK, 2B` changes one, `2: <text>` on its own line answers in one's own words (gtaw adds it as a ticked option and the analyst turns it into THEN clauses). `OK` on an approval prompt approves the revision the prompt shows; defect, escalation and naming-conflict prompts mark a recommended option that `OK` takes. `OK` only counts at the start of a reply, so "not ok" or "不同意" never take a recommendation.
- `output.language` (`en`, `zh-CN`): gtaw's prompts are shown in that language, and the analyst writes its questions in it.
- Production safety (P4): every gtaw run drops the developer's Spring profile and configuration (`SPRING_PROFILES_ACTIVE`, `SPRING_CONFIG_*`, `SPRING_APPLICATION_JSON`, and `-Dspring.profiles…`/`-Dspring.config…` in `JAVA_TOOL_OPTIONS`), on top of the existing cloud-credential isolation. The new `safety.protectedHosts` lists SIT, UAT and production hosts. An item's test files or fixtures that name one are refused at submit, before any run (`POLICY_PROTECTED_HOST`), and an Oracle `testUrl` on one is invalid. `gtaw doctor` warns until the list is set, and the author and reviewer rules say tests run locally only.
- `websocket` capability (P4, explain-only until its eval passes): WebSocket/STOMP/SSE services are detected, with the new `gta-testing-streaming-endpoints` skill.
- Catching tests and the assessor (plan §6, P3): in PR mode a failing `hint.intent=catching` test is also run on the PR's base (a temporary worktree with the base's production code and the branch's tests). gtaw refuses `product-defect` when it fails there too, and treats code missing on the base as new. The reviewer's triage brief gives the base result, the PR description and `catching-assessment.md`. A catch candidate always goes to a human, never to the auto-correction. The report counts catching hits. The Gradle e2e's PR step adds a late fee with a seeded bug (D16) that a catching test catches.
- Incident mode (plan §6, P3): `gtaw next --mode incident --target <flowId> --intent "<report>"` keeps the report as `<flow>.incidents.md` next to the spec (committed with the approval) and briefs the analyst to add a `hint.intent=reproducing` scenario that cites it. The human confirms the expectation, the author is told the test must fail on today's code, gtaw checks that it fails for the reported reason, and the human confirms the defect before it is pinned. `hint.intent` (`reproducing`, `catching`) is a linted scenario key the planner honours. `/gta incident` in the orchestrator; the Gradle e2e reproduces an overcharge.
- PR mode (plan §6, a P1 item that was still blocked): `gtaw next --mode pr [--base <ref>] [--intent "<PR description>"]` finds the flows the branch changes (through the impact analysis, plus entry points of changed classes without a spec), marks the approved ones `stale`, and works through them one after another in the same session. The analyst brief quotes the PR description as documented intent and loads `gta-analyzing-change-impact`. `/gta pr` in the orchestrator; the Gradle e2e checks it.
- P3 preview, Beam: each composite `PTransform` (its `expand`) is a `beam-pipeline` flow, and the oracle reads `PAssert.that(...).containsInAnyOrder(...)` values as expected values. The Beam skill names the test dependencies (DirectRunner, `beam-sdks-java-testing-junit`, and `junit:junit` for `TestPipeline`). `catalog-service` gains a low-stock transform (seeded D15) and the catalog e2e takes it to a pinned defect.
- P3 preview, workflows: the implementation of each Temporal `@WorkflowMethod`, the transitions of each Spring Statemachine configuration and each `*.bpmn` process are `workflow` flows; detection knows the Temporal and Camunda 7/8 Spring Boot starters. The workflows skill gains a Temporal reference (`TestWorkflowEnvironment`, faked activities). `catalog-service` gains a Temporal restock workflow (seeded D14, a missing compensation) and the catalog e2e takes it to a pinned defect; `cap_flow` takes the item's layer.
- Gradle builds (P3): with `build.tool` `auto` (default), a service with `build.gradle(.kts)` and no `pom.xml` builds with Gradle. gtaw reads the Boot plugin version, dependencies, Java level and subprojects from the build script (Groovy or Kotlin DSL) and runs tests through `GradleRunner` with an init script in `.gta/work/gradle/`, so the build script is never changed by gtaw. `build.gradle` names the executable (default `./gradlew`). Doctor checks Gradle instead of Maven, `init` accepts a Gradle build, and the foundation item may edit the build script. `gtaw mutate` and the can-fail check report Gradle builds as `n/a`. A third fixture, `invoice-service` (Gradle Kotlin DSL, seeded D13), and `evals/e2e/run-gradle.sh` take a REST flow to a pinned defect.
- P3 preview, gRPC: each RPC of a class extending a generated `*ImplBase` is a `grpc-api` flow; detection knows the Boot 4 gRPC starters, and the gRPC skill reference uses `@AutoConfigureTestGrpcTransport`. `catalog-service` gains a gRPC stock service (seeded D12) and the catalog e2e takes it to a pinned defect.
- P3 preview: `"phase": "P3"` in `.gta/config.json` opts a repository into the next phase early (never an earlier one). WebFlux handlers (returning `Mono`/`Flux`) are `webflux` flows with the new `gta-testing-reactive-apis` skill; `@QueryMapping`/`@MutationMapping` handlers are `graphql-api` flows. GraphQL documents and variables count as inputs, not expected values. A second fixture, `catalog-service` (WebFlux + GraphQL, seeded D10 and D11), and `evals/e2e/run-catalog.sh` take both flows to a pinned defect; the e2e helpers moved to `evals/e2e/lib.sh`.
- The learning loop: gtaw records what went wrong (a reviewer's reject, a test that could not fail, a test defect, a suspected product defect) in the tracked `.gta/lessons.md`, commits it with the session report, and shows the latest lessons in every brief.
- `spec.source=openspec|speckit`: the analyst's brief lists the repository's own OpenSpec or Spec Kit specs as documented sources to cite, never to edit.
- The can-fail check (plan §5.6): verify mutates the methods a unit or slice scenario's spec cites and runs only its test; a test that kills none of them passes whatever the code does and goes back to the author (`POLICY_TEST_CANNOT_FAIL`). PIT being unavailable is reported, never held against the item. `gtaw verify --item <id>` verifies one item. The e2e catches a tautological first draft and accepts the repaired test.
- `gtaw mutate --changed [--base]`: PIT on the production lines changed since the base, with the service's unit and slice tests; reports strength and names surviving mutants. PIT runs from its command line with tools resolved through the service's own Maven repositories (no plugin in the service's pom; the JUnit Platform launcher is added at the service's version). Advisory. The e2e checks it names the surviving boundary mutant of the fixed D1 line.
- P2 capabilities write tests (current phase P2): `security` and `caching` add their skills to the flows of a service that uses them; Spring Batch jobs and Flyway/Liquibase migrations are flows. New `file:` spec refs hash non-Java files such as migration scripts (stale specs, `gtaw impact`). Detection knows the Boot 4 starter names. Following `adding-a-capability.md`, the fixture gains a seeded defect per capability (D6-D9) and the e2e takes each flow to a pinned defect.
- P2 groundwork: `security` and `caching` capabilities (detected; they add the new `gta-testing-spring-security` and `gta-testing-caches` skills to a service's flows from P2). Cross-cutting capabilities now follow their phase like the others. Spring Batch jobs (`@Bean Job`) are detected as flows.
- `gtaw impact` follows the bean graph (a class that injects a changed class, transitively, and implementations of a changed interface) and config refs whose value changed, and says why each case needs review (`reasons`).
- `gtaw init --ci` installs the CI gate workflow `.github/workflows/gta-gate.yml` as a managed file (kept current by `gtaw upgrade`), pointed at the service module's reports.
- P1 capabilities write tests: message listeners (Kafka, Pub/Sub, JMS/MQ, and now RabbitMQ, SQS and application events) and scheduled jobs (`@Scheduled`, ShedLock, and now Quartz jobs). The oracle lock reads `verify(producer).send(...)` arguments as expected values, while what a test sends stays an input.
- Multi-module Maven repositories: `build.module` names the service module; tests, fixtures and reports live in it, Maven runs from the root with `-pl <module> -am`, and the Boot version and Java level are inherited from the root pom. An aggregator without `build.module` is refused with a hint; `gtaw doctor` checks the setting. The scripted e2e also runs as a module (`GTA_E2E_MODULE`).
- Services on Java 8 and 11: `gta-junit` is Java 8 bytecode, briefs name the service's level and `javax.*` for Spring Boot 2, `build.javaHomeEnv` runs Maven with the JDK an old build needs, and `gtaw doctor` warns (instead of failing) for Java 8 to 16.

### Changed
- Scope: gta does not test deployed environments (SIT, UAT, production), now or later, because teams test SIT and UAT themselves. The `deployed-runtime` capability, the `gta-testing-deployed-systems` skill, `gtaw env`, the Karate system layer and BigQuery temporary datasets are removed from the roadmap (plan §7.7, §15). `gtaw run --layer system` says so.
- P3 is the current phase: WebFlux, gRPC, GraphQL, workflows and Beam write tests by default, without `"phase": "P3"`. Deployed-system tests (P4) stay explain-only; `"phase": "P4"` previews them. The catalog e2e runs without a `phase` setting, and its CI job is now `e2e (catalog)`.
- CI: the four scripted e2e runs (main, multi-module, catalog, Gradle) run as parallel jobs instead of one after another in `build (21)`, with the same scripts and coverage, so a PR waits about 17 minutes instead of about 41. A newer push to the same PR cancels the run it replaces; runs on `main` are never cancelled.
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
