# Review round 3: findings and implementation plan

> **Scope.** The whole repository on `main` at `0a01906` (after PR #3). That covers:
> - `toolkit/`: 65 main and 19 test Java files, about 8,900 main LOC;
> - `core/` and `platforms/`;
> - the generated `copilot/` and `opencode/`;
> - `wrapper/`, `scripts/`, `evals/`, `docs/` and CI.
>
> **Method.** Every conclusion comes from code that was read or a command that was run:
> - the package import graph;
> - JaCoCo over the unit tests;
> - a palantir-java-format dry run on a scratch copy;
> - greps for hard-coded values, residual files, personal paths and secrets;
> - reading every class named in §6.
>
> **Status.** Final; implementation follows directly. Decisions already made are marked **[decided]**. Open questions are collected in §10. A decision this plan disagrees with is listed there as "proposal to revise", and is implemented as decided until it is answered.

## 0. Guiding principle

Clarity beats cleverness. A change is made only when it removes a real defect, a real ambiguity, or a requirement from this round. Wherever an earlier round-3 draft proposed something that adds structure without enough benefit, it is **dropped here** and listed in §8 with the reason.

## 1. Summary

| Area | Verdict | Evidence |
|---|---|---|
| Design and correctness model | **Sound.** gtaw is deterministic, writes are atomic, merged files fail closed, the commit ledger works, an e2e covers the loop, and CI runs on Linux and Windows. | AGENTS.md rules; `AtomicFiles`, `WorkLock`, `CommitLedger`; `evals/e2e/run.sh` |
| Enterprise grade | **Not yet.** There is no formatter, static analysis, coverage gate, enforcer, Maven wrapper, pom metadata or governance files. CI tests JDK 21 only, although the product promises Java 17. | root `pom.xml`, `.github/workflows/ci.yml` |
| Coverage | **gta-core 47.0 % lines and 32.3 % branches** against a 90 % gate **[decided]**. | §3.3 |
| Formatting | Clean but not enforced. palantir-java-format would change 81 of 84 Java files (+2,134 / −937 lines, wrapping only). | §3.1 |
| Package layout | No package cycles. Four edges point the wrong way, and `ItemWorkflow` (655 lines) mixes seven jobs. | §2.2 |
| Naming | Five class names mislead. `evals/` and `docs/` mix kinds of files. Test names do not mirror sources: 48 of 65 main classes have no own test, and generated service tests are named after the flow, not the source. | §2.3, §4.1 |
| Generic-ness | 21 hard-coded spots: 9 are fixed, 2 are by design, and the rest are minor or deferred. | §2.5 |
| Leftovers | Nothing stray is tracked, and there are no secrets or personal paths. `.gitignore` is incomplete, and session reports live in an ignored folder. | §6.2 |
| Bugs | 3 high, 10 medium and 9 low. | §6.3 |

## 2. Code and architecture review

### 2.1 Enterprise and engineering-standard grade

| Check | Today | Conclusion |
|---|---|---|
| Reproducible build | Plugins are pinned and Java release is 17. There is no Maven wrapper, and no enforcer for the Java/Maven floor or dependency convergence. | **Must fix:** add `mvnw` and `maven-enforcer-plugin`. |
| Formatting | Not enforced. | **Must fix [decided]:** Spotless with palantir-java-format, in its own commit (§3.1). |
| Static analysis | None. | **Must fix [decided]:** SpotBugs (§3.2). |
| Coverage | None measured. | **Must fix [decided]:** JaCoCo with a 90 % gate (§3.3). |
| pom metadata | Name and description only. | **Should fix:** add `url`, `licenses` (Apache-2.0 **[decided]**) and `scm`. `distributionManagement` waits for a real Nexus URL (§10, Q-R6). |
| Governance | LICENSE and AGENTS.md only. | **Should fix:** add CONTRIBUTING.md, SECURITY.md, CHANGELOG.md, a PR template and Dependabot (Maven and Actions). |
| CI | Linux and Windows, JDK 21. | **Must fix:** build on JDK 17 and 21. |
| Error model | `PREFIX_DETAIL` codes, the category sets the exit code, every throw has a hint. | ✅ Fine. |
| State integrity | Atomic writes, a lock, `WorkState.move` and the ledger. | ✅ Fine, except the git failures that fail open (§6.3 H1). |

### 2.2 Packages, folders and dependency direction

The modules are correct. Their responsibilities and dependencies:

| Module | Responsibility | Depends on |
|---|---|---|
| gta-core | the engine | – |
| gta-cli | the CLI | gta-core |
| gta-junit | on the services' test classpath | nothing |
| gta-harness-gen | generates `copilot/` and `opencode/` | gta-core |

The gta-core import graph has **no cycles**. Its layers, from bottom to top:

```
util, error → config → registry, profile → spec → plan → state → oracle, change, run → gate, report, brief → engine
```

| # | Finding | Evidence | Fix | Class |
|---|---|---|---|---|
| A1 | The oracle domain depends on the shape of the state file | `oracle/OracleLock.java:3` builds `WorkState.Lock` | Move the type to `oracle.OracleLock.Lock` | should |
| A2 | The change domain depends on the shape of the state file | `change/ChangeChecker.java:4` uses `WorkState.Baseline` | Move the type to `change.Baseline` | should |
| A3 | Scenario revision hashing sits in the planner, but the gate, engine and relock use it | `Planner.java:117`, `Gate.java:121,160`, `Engine.java:323`, `ItemWorkflow.java:471` | Add `Spec.Scenario#revision()` | should |
| A4 | The ref parsing `ref.substring(ref.indexOf(':')+1, ref.indexOf('#'))` is repeated, with different edge cases each time | `Planner.java:126`, `Briefs.java:240`, `Engine.java:348`, `Gate.java:279`, `RefResolver.java:43,46` | Add one `spec.Ref` value type | must (it causes M4 and M5) |
| A5 | `ItemWorkflow` does seven jobs | `engine/ItemWorkflow.java`, 655 lines | Split it into `ItemWorkflow` (dispatch, check, submit), `Triage` (triage, auto-correct, human decisions), `ItemCommitter` (commit, pins) and `PlanSync`. `Triage` then gets its own coverage rule (§3.3) | should |
| A6 | `StateStore.load`/`peek` and `loadPlan`/`peekPlan` are copies of each other | `StateStore.java:26-99` | One private reader | optional (done with M1) |
| A7 | The fingerprint algorithm is copied between gta-junit and gta-core (gta-junit must not depend on gta-core) | `FailureClassifier.java:83`, `gta-junit/Fingerprint` | Keep both copies, plus a test that asserts both produce identical output on shared vectors (gta-junit as a test dependency of gta-core) | should |
| A8 | Module resources reach outside their own folder (`../../core`, `../../platforms`, `../../wrapper`) | `toolkit/*/pom.xml` | Document it in AGENTS.md. A separate resource module is not worth its cost | optional |

### 2.3 Names versus content

**Renamed** (the current name misleads):

| Current | Why it misleads | New |
|---|---|---|
| `run/TestCase` | It holds a test's result. It also clashes with JUnit 3 `TestCase`, which Surefire's default includes match (`**/*TestCase`). | `TestOutcome` |
| `brief/Briefs` | It writes brief files and issues tokens. | `BriefWriter` |
| `util/Proc` | An abbreviation. | `ProcessRunner` |
| `run/SurefireReports` | It is a parser. | `SurefireReportParser` |
| `registry/Registry` | A generic word, used across packages. | `CapabilityRegistry` |
| `core/spec/SpecFixtures` (test) | A helper whose name looks like a test subject. | `SpecTestData` (§4) |
| `engine/FakeTestRunner` (test) | It is support code. | `TestRunnerFake` (§4) |

Several more renames were considered and dropped as not worth the churn: `Gate` (clear inside `gate`), `Profiler`, `NodeResults` and `HarnessGen`.

**Folders**

| Path | Finding | Fix |
|---|---|---|
| `docs/design/remediation-plan.md` | A review record inside `design/`. | `docs/reviews/round-2-remediation.md` **[decided: reviews in docs/reviews/, English only]** |
| `docs/quickstart.md`, `docs/ci-integration.md`, `docs/adding-a-capability.md` | Guides at the docs root. | `docs/guides/` |
| `evals/e2e/agent/` | It holds scripted agent **output**. `OrderSeedIT.java` is the only `.java` file there; the rest are `.java.txt`. | Rename the folder to `evals/e2e/scripted-output/`. The files are renamed to the names gta now generates (§4.3). |
| `copilot/`, `opencode/` | Generated, but nothing on disk says so. Outsiders take `copilot/.gta` and `copilot/.vscode` for leftovers. | The generator writes a `README.md` that says "generated, do not edit". |

### 2.4 Formatting, structure and comments

| Check | Result |
|---|---|
| Tabs, trailing whitespace, star imports | none ✅ |
| Lines over 120 | 87. They remain long string literals after formatting, which is acceptable to palantir. |
| Class Javadoc | missing on `GtaCli` and `Hashes` |
| Wrong comment | `Engine.java:260`: the Javadoc on `abort` says "Read-only", but `abort` discards state. |
| Member order | constants appear between methods (`MavenRunner.java:57`, `GtaPaths.java:38`) |

Fixed by the format commit, two Javadocs and the comment fix.

### 2.5 Generic-ness: hard-coded, special-cased or business-coupled

| # | Location | What | Decision |
|---|---|---|---|
| G1 | `cli/DoctorChecks.java:66-71` | Oracle and Pub/Sub checks run for every service | **Fix:** run them only when the pom has the dependency (`ojdbc*`, `*pubsub*`). |
| G2 | `config/GtaConfig.java:45-55` | An `environments.oracle` section | **By design:** an optional environment the Oracle skill reads, not business coupling. Documented as such. |
| G3 | `MavenRunner.java:89`, `SessionEnd.java:101`, `GtaCli.java:338` | Stability variant fixed to `Asia/Kolkata` and `de-DE` in three places | **Fix:** `qualityGates.stabilityTimezone` and `qualityGates.stabilityLocale`, with today's values as defaults. One message builder. |
| G4 | `engine/TestRuns.java:51` | Smoke run pattern `*SeedIT` | **Fix:** run the planned foundation test class (§4). |
| G5 | `plan/Planner.java:43,72` | Test class = domain + flow + `IT`/`Test`; seed `<Domain>SeedIT` | **Fix:** §4 mapping. |
| G6 | `plan/Planner.java:64,90-93` | Fixture folder `fixtures/<domain>/<flow>`. The foundation owns all of `src/test/resources/**`. | **Fix:** §4 resource mapping. The foundation owns `support/**`, `application-test.*` and `pom.xml`. |
| G7 | `GtaPaths.java:11-14,121`, `MavenRunner.java:62` | Single-module Maven layout; `target/` paths are fixed | **Deferred:** multi-module support is a product feature (§10, Q-R7). |
| G8 | `MavenRunner.java:57` | `ALL_TESTS` include list | **Fix:** derived from the §4 suffix table. |
| G9 | `FailureClassifier.java:17-21` | Framework prefixes include `com.google.`, `org.apache.` and `oracle.`. A service whose own code lives under one of them would be misclassified. | **Fix:** frames under the service's base package are never treated as framework. |
| G12 | `ChangeChecker.java:25-29` | Protected platform paths duplicate `platforms/*/mapping.yaml` | **Fix:** every path in `.gta/managed-files.json` is protected too, so a new platform file cannot be missed. |
| G14 | `Engine.java` `step()` | Falls back to the `rest-api` capability for an unknown flow | **Fix:** USAGE_UNKNOWN_FLOW with a hint. |
| G16 | `Profiler.java:192-194` | Only the first path of `@GetMapping({"/a","/b"})` is kept | **Fix:** keep every path. |
| G17 | fingerprint copies | see A7 | **Fix:** equality test. |
| G19 | `core/skills/gta-testing-deployed-systems/references/karate.md:4` | `KarateSystemSuite` | **Fix:** §4 Karate rule. |
| G10, G11, G13, G15, G18, G21 | minor values (2 re-runs, the verdict list, the `nexus.example.com` placeholder, example ids in messages, assertion message shapes, `build.maven` as a single string) | **Kept.** Each is a sensible default or a placeholder that the doctor already flags. |
| G20 | Spring annotations only | **By design** (P0 scope, see README). |

## 3. Toolchain gap analysis **[decided]**

### 3.1 Formatting: palantir-java-format, 4 spaces, 120 columns, Spotless

- Spotless runs `check` in `verify` and fails the build on drift. `mvn spotless:apply` fixes the drift locally.
- A dry run on a scratch copy changed **81 of 84 Java files (+2,134 / −937)**. These are wrapping, import order and blank lines only; no tokens change.
- Scope: `toolkit/*/src/{main,test}/java`.
- **Not formatted:** `evals/fixtures/order-service`. It stands in for a third-party service, and the e2e compares its structure.
- The reformat is **one commit containing only the reformat**. Its SHA goes into `.git-blame-ignore-revs`.

### 3.2 SpotBugs

- `spotbugs-maven-plugin` runs `check` in `verify` with effort `Max` and threshold `Medium`.
- One exclude file (`toolkit/spotbugs-exclude.xml`) holds only documented exceptions. `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` are excluded on the persisted DTOs, which have public fields so Jackson can map them (§10, Q-R8).
- Every other finding is fixed, not excluded.

### 3.3 Coverage: JaCoCo, gta-core ≥ 90 % lines, hard gate

**Today** (unit tests only, `mvn test` with the JaCoCo agent):

| Package | Lines | Branches | Missed lines |
|---|---|---|---|
| brief | 1.6 % | 0 % | 181 |
| report | 0 % | 0 % | 86 |
| profile | 17.3 % | 5.1 % | 235 |
| engine | 26.4 % | 14.7 % | 895 |
| run | 28.3 % | 25.6 % | 165 |
| gate | 65.6 % | 51.9 % | 66 |
| util | 71.1 % | 57.7 % | 58 |
| spec | 72.7 % | 57.1 % | 133 |
| registry | 76.3 % | 50.0 % | 9 |
| error | 82.4 % | 0 % | 18 |
| state | 84.3 % | 46.9 % | 14 |
| change | 87.5 % | 78.3 % | 13 |
| config | 87.6 % | 42.9 % | 12 |
| oracle | 88.0 % | 70.0 % | 25 |
| plan | 93.1 % | 57.1 % | 7 |
| **gta-core** | **47.0 %** | **32.3 %** | about 1,920 |

For comparison: gta-cli 41.7 %, gta-junit 77.5 %, gta-harness-gen 63.9 % (no gate on these).

**Gate**

`jacoco:check` in `verify` of gta-core, failing the build:

| Element | Lines | Branches |
|---|---|---|
| gta-core bundle | ≥ 90 % | – |
| packages `oracle`, `state`, `run` and class `engine.Triage` (the core decisions) | ≥ 90 % | ≥ 80 % (Q-R2) |

CI runs `mvn verify`, so a PR below the gate cannot go green.

**Tests to add**, in order of missed lines. Each one asserts documented behaviour from `docs/design/plan.md` (the toolkit's spec), never a snapshot of today's output.

| # | Module or class | Tests |
|---|---|---|
| 1 | engine (`SpecWorkflow`, `ItemWorkflow`, `Triage`, `SessionEnd`, `TestRuns`, `HumanPrompts`, `EscalationWriter`) | One test class per source class, driving the documented loop through `TestRunnerFake` and a real temporary git repository. |
| 2 | `profile` (`ServiceProfiler`, `PomInfo`, `JavaIndex`, `RefResolver`) | Tests on small Java/pom fixtures under `src/test/resources/<package>/<TestClass>/`. |
| 3 | `run` (`MavenRunner`, `SurefireReportParser`, `FailureClassifier`) | `MavenRunner` is tested with a fake `mvn` script (through `build.maven`) that writes Surefire XML. No real Maven is needed. |
| 4 | `brief`, `report` | `BriefWriter`, `Reporter`. |
| 5 | `gate`, `spec`, `util` | The remaining branches. |

Test hygiene:
- No "call without assert" tests.
- Each test name states the behaviour (`shouldRevertOutOfScopeEditsWhenGitStatusFails`).
- Assertions use the values that the plan or spec states (an error code, a state, a file content), never values copied from a run.

## 4. Naming and placement of tests and generated artifacts

### 4.1 The rule **[decided]**

A test is named `<SourceSimpleName><Suffix>`. It sits in the **same package** as its source, in the source root of its tool. So a test class name **maps back to exactly one source**, and each suffix appears **at most once per source**. Growth goes into JUnit `@Nested` classes, never into sibling files.

Today:
- In this repo, 48 of 65 main classes have no mirrored test. `EngineTest` covers 8 classes. `AssertionScannerTest` also covers `OracleLock`.
- In generated service tests, `Planner` names the test after the flow (`OrderCreateOrderIT`, not `OrderCreateIT`), in the package of the first referenced class, and the seed is `<Domain>SeedIT`. Neither maps to a source.
- Karate uses one `<base>.system.KarateSystemSuite`.

### 4.2 Suffix table

| Kind | Tool | Suffix | Source root | Example | Who runs it, configured explicitly |
|---|---|---|---|---|---|
| Unit (no Spring context, no I/O) | JUnit 5, Mockito, AssertJ | `Test` | `src/test/java` | `controller/ExchangeReportController` → `controller/ExchangeReportControllerTest` | Surefire `test` phase |
| Spring slice | `@WebMvcTest`, `@WebFluxTest`, `@DataJpaTest`, `@JdbcTest`, `@JsonTest`, `@RestClientTest`, … | `SliceTest` | `src/test/java` | `controller/ExchangeReportControllerSliceTest` | Surefire `test` phase |
| Integration (full context, or embedded or faked dependencies) | `@SpringBootTest`, WireMock, embedded Kafka or JMS | `IT` | `src/test/java` | `controller/ExchangeReportControllerIT` | Failsafe `integration-test` phase |
| Application seed (context starts) | `@SpringBootTest` | `IT` on the `@SpringBootApplication` class | `src/test/java` | `OrderApplication` → `OrderApplicationIT` | Failsafe |
| Contract, provider side | Spring Cloud Contract or Pact | `ContractIT` | `src/test/java` | `controller/OrderControllerContractIT` | Failsafe |
| Contract, consumer side | Pact | `PactTest` | `src/test/java` | `client/PaymentClientPactTest` | Surefire |
| API or system | Karate | `Karate` | `src/test/karate/java` (package mirrored) | `controller/ExchangeReportControllerKarate` | `karate` profile only |
| Karate features | Karate | `<flow-name>.feature` | `src/test/karate/resources/<package>/<SourceKarate>/` | `…/ExchangeReportControllerKarate/export-report.feature` | the runner above |
| End-to-end | JUnit 5 against a running system | `E2ETest` | `src/test/e2e/java` (package mirrored) | `controller/ExchangeReportControllerE2ETest` | `e2e` profile only |
| Batch job / scheduler / listener | Spring Batch test, Awaitility, embedded broker | `IT` on the job configuration, scheduler or listener class | `src/test/java` | `batch/SettlementJobConfigIT` | Failsafe |
| Beam pipeline | `TestPipeline` | `Test` | `src/test/java` | `pipeline/EnrichPipelineTest` | Surefire |
| Performance (later) | Gatling | `Simulation` | `src/test/gatling/java` | `controller/OrderControllerSimulation` | `gatling` profile |
| Architecture rules | ArchUnit | `ArchitectureTest` (exception E3) | `src/test/java/<base>/architecture/` | `ArchitectureTest` | Surefire |

**Build tool defaults, and why gta configures them explicitly.**

| Plugin | Default includes | What the suffixes mean for it |
|---|---|---|
| Surefire | `**/Test*.java`, `**/*Test.java`, `**/*Tests.java`, `**/*TestCase.java` | Matches `Test`, `SliceTest`, `PactTest`, and also `E2ETest` if `src/test/e2e/java` were a test source root. |
| Failsafe | `**/IT*.java`, `**/*IT.java`, `**/*ITCase.java` | Matches `IT` and `ContractIT`. |

Neither plugin matches `Karate` or `Simulation`, and Surefire would wrongly match `E2ETest`. So nothing relies on defaults:

1. **gtaw runs**: `MavenRunner` always passes an explicit `-Dtest=` list taken from this table (unit and slice: `*Test`, `*SliceTest`; integration: `*IT`, `*ContractIT`; Karate and E2E only for `--layer system`). Surefire's own `includes` are never used.
2. **The service's own build**: the foundation item does **not** edit the developer's Surefire or Failsafe configuration. Instead, `gtaw doctor` checks it, and `docs/guides/ci-integration.md` gives the exact snippet:
   - Surefire `includes` = `**/*Test.java` and `**/*SliceTest.java`, `excludes` = `**/*E2ETest.java`;
   - Failsafe `includes` = `**/*IT.java`;
   - `karate` and `e2e` profiles that add their source roots with `build-helper-maven-plugin`.

   This is Q-R4.
3. **This repository** (the toolkit): Surefire `includes` is set to `**/*Test.java`. The toolkit has no `IT`.

**Support code** (never ends in a test suffix, so no runner picks it up):

| Kind | Suffix | Placement |
|---|---|---|
| Fake: a working, lightweight implementation of a port | `Fake` | same package as the port (`domain/OrderRepositoryFake`) |
| Stub for a downstream system | `Stub` | `<base>/support/stub/` (`PaymentGatewayStub`) |
| Mock | no class; inline Mockito | – |
| Test data builder | `TestData` | same package as the built type (`domain/OrderTestData`) |
| Test configuration | `TestConfig` | same package as the configuration it replaces |
| JUnit extension | `Extension` | `<base>/support/` |

**Test resources**

| Kind | Path |
|---|---|
| Fixtures of one test class | `src/test/resources/<package path>/<TestClass>/<sNN>.<role>.<ext>`, e.g. `com/acme/order/api/OrderControllerIT/s01.request.json` |
| Golden output | the same folder, `<sNN>.approved.<ext>` |
| WireMock files (the tool's own layout) | `src/test/resources/wiremock/<downstream>/mappings/` and `__files/` |
| Spring test properties | `src/test/resources/application-test.yml` |

**Generated artifacts**

| Artifact | Path | Tracked |
|---|---|---|
| Spec | `.gta/specs/<domain>/<flow>.spec.md` | yes |
| Oracle shard | `.gta/specs/<domain>/<flow>.oracles.json` | yes |
| **Case ledger** (§5) | `.gta/specs/<domain>/<flow>.cases.md`; overview `.gta/specs/INDEX.md` | yes |
| Escalation and defect records | `.gta/escalations/<itemKey>.md` | yes |
| **Session report** | `.gta/reports/<yyyy-MM-dd>-<sessionId>.md` **[decided]** | yes |
| PR comment draft, briefs, runs, snapshots, ledger of SHAs, state | `.gta/work/**` (purely temporary) **[decided]** | no |
| gta-junit results | `target/gta-results/*.jsonl` | no |

### 4.3 Mapping algorithm, as gtaw implements it

```
source  = outer class of the flow's entry ref        (method:com.acme.order.api.OrderController#create)
suffix  = SUFFIX[layer]     unit→Test, slice→SliceTest, component→IT, contract→ContractIT, system→Karate
test    = <root of tool>/<source package path>/<SourceSimple><suffix>.java
flow    = @Nested class <Pascal(flow-name)> inside test
method  = tagged @Tag("scenario:<flowId>/<sNN>")       (unchanged: results map by tag)
fixture = src/test/resources/<source package path>/<SourceSimple><suffix>/
seed    = <SpringBootApplication class>IT, in its package (one per application)
ownsFiles(item) = { test, fixture/** }; seed adds support/**, application-test.*, pom.xml
```

- Two flows of one controller share a test file, each in its own `@Nested` class.
- `AssertionScanner` already finds scenario methods by tag. A unit test covers the `@Nested` case.
- The e2e expectations become `OrderControllerIT` and `OrderApplicationIT`.

### 4.4 Exceptions

| # | Case | Rule |
|---|---|---|
| E1 | Types with no behaviour of their own: records and DTOs, enums without logic, interfaces, annotations, exception classes, constants | No own test. They are tested through their users and listed in the allowlist (below). |
| E2 | Nested classes | Tested in the outer class's test. |
| E3 | Cross-cutting tests with no single source (architecture rules, repository layout, the shell e2e) | Named after their subject, and listed in the allowlist. In this repo: `RepositoryHarnessTest`, `evals/e2e/run.sh`. |
| E4 | A test file grows too big | `@Nested` per method or flow. If it is still too big, split the source. |
| E5 | **Naming conflicts**: the target test name is taken by a file gta does not manage (developer-owned), or two sources map to one name | gtaw **stops that item** and raises a `naming-conflict` decision that names the file and its last author. Options: A, add a gta `@Nested` class to that file (gta locks only its tagged methods); B, skip this layer for that source; C, the developer renames or moves the file, then gtaw re-checks. **There is no default.** The item waits until the user answers, and other items continue. |
| E6 | Tool-mandated layouts (WireMock, Spring, Karate classpath) | The tool's layout wins. |
| E7 | Generated production sources (OpenAPI, MapStruct, Lombok output) | No tests. |
| E8 | Default package | The test goes in the default package too. The code never assumes a dot (bug M4). |

**Enforcement in this repo.** `MirroredTestNamesTest` (gta-harness-gen) fails when:
- a main class has no `<Name>Test`, unless the allowlist `toolkit/test-mirror-allowlist.txt` names it with a reason;
- a test class does not map back to a main class, unless it is a support class (`*Fake`, `*TestData`) or on the allowlist.

Services get the E5 check at planning time; a service-side lint command was dropped (§8).

## 5. Spec and test case ledger **[decided]**

### 5.1 Who owns what

| Document | Owns | Never contains |
|---|---|---|
| `<flow>.spec.md` | the behaviour contract: requirements, scenarios with GIVEN/WHEN/THEN, the diagram, questions. It is the only source of expected values and takes effect once approved. | test status |
| `<flow>.cases.md` | the test-asset ledger: one row per scenario, with a one-sentence summary, the link to the spec, the automation, priority, the latest result and status | steps or expected values copied from the spec |
| `.gta/specs/INDEX.md` | overview and coverage matrix across flows | case details |

### 5.2 Ledger template

```markdown
# Test cases: order.create

<!-- gta:cases v1. Maintained by gtaw. Edit only the Status column (Retired) and the Manual cases table. -->
Spec: [create.spec.md](create.spec.md) · rev R3

| Test Case ID | Test Title | Test Content | Expect Result | Linked Spec | Automation | Priority | Test Result | Status |
|---|---|---|---|---|---|---|---|---|
| order.create/s01 | Create an order | Valid basket is submitted | Order is created | create.spec.md#s01 | com.acme.order.api.OrderControllerIT$CreateOrder#s01_createsOrder | P0 | Pass 2026-11-04 a1b2c3d | Active |
| order.create/s02 | Reject an empty basket | Empty basket is submitted | Request is rejected | create.spec.md#s02 | com.acme.order.api.OrderControllerSliceTest$CreateOrder#s02_rejectsEmptyBasket | P1 | Fail 2026-11-04 a1b2c3d | Needs Review |

## Manual cases

| Test Case ID | Test Title | Test Content | Expect Result | Priority | Test Result | Status |
|---|---|---|---|---|---|---|
| order.create/m01 | Exploratory: odd currencies | … (full text allowed here) | … | P2 | Not run | Active |
```

Column values:

| Column | Values |
|---|---|
| **Test Content** / **Expect Result** | One sentence, written by gtaw from the scenario's WHEN and THEN titles, never their full text. |
| **Automation** | Test class FQCN plus method, or `Manual`. |
| **Priority** | `P0` core path, run every time · `P1` important branch · `P2` edge case. The analyst sets it per scenario as `priority:` in the scenario meta; if it is missing, gtaw derives it (Q-R3). |
| **Test Result** | `Pass` or `Fail` + date + short SHA, or `Not run`, or `Known defect`. Only the latest result; history lives in reports. |
| **Status** | `Active` · `Needs Review` · `Retired`. |

The ledger is machine-parseable: a fixed header comment, fixed columns, one row per id. A CSV export is **not** built now **[decided]**.

### 5.3 How the ledger is maintained (no full rewrite)

`CaseLedger` parses the file and **upserts single rows**; rows it doesn't touch are written back byte-for-byte.

| Hook (existing code) | Ledger change |
|---|---|
| Spec approved (`SpecWorkflow.applyApproval`) | Add a row for each new scenario (Not run, Active). Changed scenarios become Needs Review; removed scenarios become Retired. |
| Item committed (`ItemCommitter.commitItem`: passed or known defect) | Set Automation, Test Result and Status=Active on that row, **in the same commit** as the test. |
| Item failed and triaged, or unstable in `verify` | Test Result = Fail, Status = Needs Review. |
| Session end (`SessionEnd.finishSession`) | Results of the gate's full run update every row. INDEX.md is rewritten (it is derived). The report lists every row that changed this session and the retired cases waiting for confirmation. gtaw commits the report and the ledger changes (Q-R5). |
| `gtaw impact --base <ref>` | Maps the production files changed since `<ref>` to spec refs and then to cases, and marks them Needs Review. |

The link to the existing listener: `GtaResultsListener` writes the tags of each executed test, `SurefireReportParser` merges them into `TestOutcome.scenarioId()`, and the ledger keys rows by that id. No new listener is needed. The tag-loss bug H3 must be fixed first.

**Regression scope.** A session re-verifies the Needs Review cases plus every P0 case. **[decided]** (see Q-R1 on the CI gate.)

**Retiring cases [decided].** A case is retired when:
- its scenario leaves the approved spec, or
- a human sets its Status cell to `Retired`.

Its test is kept and still runs. Each report lists every retired case. Only `gtaw cases confirm-retired <ids>|--all`, run by a human, deletes the tests: gtaw removes the methods tagged with the scenario (JavaParser, formatting preserved) and the class if it becomes empty, deletes the shard entry and the row, and commits. Nothing is ever deleted automatically.

**First ledger for an existing service.** `gtaw cases --rebuild` builds the ledger from the approved specs, the shards and the committed tests. Test Results stay `Not run` until the next session or `gtaw run`.

### 5.4 Coverage matrix in `INDEX.md`

| Flow | Scenarios | Automated | Manual | Pass | Fail | Needs Review | Retired | P0 pass |
|---|---|---|---|---|---|---|---|---|
| order.create | 4 | 4 | 0 | 3 | 1 | 1 | 0 | 2/2 |

## 6. Architecture re-review, leftovers and bugs

### 6.1 Architecture

See §2.2 (A1–A8). There is no cross-module coupling beyond A7 and A8. One misplaced responsibility (A5) is fixed by the split. Reports move out of `runs/` into tracked `.gta/reports/` (§5.3).

### 6.2 Leftovers (goal: shareable as-is)

| Path | What | Action |
|---|---|---|
| `toolkit/*/target/`, `evals/fixtures/order-service/target/` | Local build output; ignored, not tracked | None. Share with `git clone` or `git archive`. |
| `.gitignore` | Only `target/`, `*.iml`, `.idea/` | Add `.DS_Store`, `Thumbs.db`, `__pycache__/`, `*.py[cod]`, `*.log`, `*.swp`, `/.vscode/`, `/.claude/`. |
| `copilot/.gta`, `copilot/.vscode`, `opencode/.gta` | Generated, intended | Add the generated README (§2.3). |
| `docs/design/remediation-plan.md`, root-level guides, `evals/e2e/agent/` | Misplaced | Move them (§2.3). |
| `evals/e2e/run.sh:23` | `jget` runs Python `eval` on an argument | Replace with a key-path lookup. |
| Commit history | AI authorship and session links | **Kept [decided].** |
| LICENSE | Apache-2.0 | **Kept [decided].** |

Checked and clean: no tracked binaries, caches, logs, IDE files, TODO/FIXME markers, personal paths or secrets.

### 6.3 Bugs, by severity

Each fix starts with a failing test.

**High: must fix**

| # | File:line | Defect | Fix |
|---|---|---|---|
| H1 | `util/Git.java:41-43,34` → `change/ChangeChecker.java:55,66` | The scope check fails open. When `git status` fails (dubious ownership, `index.lock`), `dirtyPaths()` returns nothing, so out-of-scope edits pass. `head()` returns a zero SHA. | `dirtyPaths`, `head` and `commitsSince` throw `ENV_GIT_FAILED` (with a hint) instead of returning empty. |
| H2 | `engine/ItemWorkflow.java:526-577`, `util/Git.java:127-145` | The commit is not atomic. A failed `git commit` (gpg signing, no `user.email`) leaves the shard written and staged, and the next submit blames the agent for changing a protected path. "nothing to commit" is matched in English, so a `zh_CN.UTF-8` git breaks it. | Run git with `LC_ALL=C`. Check `git diff --cached --quiet` instead of parsing text. On failure, unstage and restore the shard, so the state stays consistent and the error says what to fix. |
| H3 | `run/SurefireReports.java:67,43`, `gta-junit/GtaResultsListener.java:55` | Parameterized and repeated tests (`name(Type)[1]`) do not match the listener's bare method name, so they lose their scenario tags. The gate then reports a passing scenario as unverified. | Normalise to the method name before `(`; merge every invocation. |

**Medium: must fix**

| # | File:line | Defect | Fix |
|---|---|---|---|
| M1 | `ItemWorkflow.java:316,352` | `ErrorCode.valueOf` on a persisted string crashes on codes removed in round 2. | `ErrorCode.find(name)` returns empty for an unknown code; callers then treat the failure as unclassified (triage, the safe default). |
| M2 | `util/Proc.java:47-56`, `MavenRunner.java:109` | On timeout the output is lost, and on Windows the log file is still locked when it is deleted. | Wait for exit after kill, keep the output, delete the log best-effort. |
| M3 | `ChangeChecker.java:123-124`, `Git.java:91-93` | Restoring a file that is staged but not in HEAD silently fails, and the file is reported as restored. | Check the result; such files are removed from the index and deleted (after backup). |
| M4 | `Planner.java:121-133` → `Briefs.java:249` | Default package: `substring(0,-1)` throws. | `Ref`/package helpers (A4). |
| M5 | `GtaPaths.java:76-82`, `Ids.java:42-46`, `PlanItem.java:38`, `Engine.java:348` | Unchecked `indexOf` on flow ids and refs. | Validate at the boundary (A4); `GtaPaths.spec` throws USAGE_UNKNOWN_FLOW. |
| M6 | `gate/Gate.java:118` | A malformed key in an untrusted shard crashes the gate. | Report GATE_SHARD_UNREADABLE. |
| M7 | `wrapper/gtaw:28,30` | The Nexus password is on curl's command line; wget ignores credentials. | curl reads `-K -` from stdin; wget gets `--user`/`--password` from the environment. |
| M8 | `MavenRunner.java:146-155` | A failed deletion of old reports is ignored, so stale XML is read as current. | Fail with ENV_REPORTS_LOCKED when the directory cannot be cleared. |
| M9 | `SurefireReports.java:82,110-116` | Unreadable XML or one bad JSONL line silently drops results. | Report-level error TEST_REPORT_UNREADABLE; bad lines are skipped one by one and counted. |
| M10 | `ItemWorkflow.java:604-617` | `syncPlan` resets an item that has a pending human decision, but `st.pending` still points at it. | Clear `st.pending` when its item is replaced (after a reproducing test). |

**Low: should fix**

| # | File:line | Defect | Fix |
|---|---|---|---|
| L1 | `SessionManager.java:47-57` | Recovery catches only `GtaException`. | Also catch `UncheckedIOException` and report the backup failure. |
| L2 | `SessionManager.java:41` | A hand-edited `lastSeenAt` crashes. | Treat an unparseable value as idle. |
| L3 | `WorkLock.java:27-41` | The channel leaks if `tryLock` throws. | try/finally. |
| L4 | `wrapper/gtaw:24-33` | Two first runs share one `.part` file. | Use a `mktemp` file next to the cache. |
| L5 | `wrapper/gtaw:28-30` | A download failure is not a JSON error. | Print the JSON error object. |
| L6 | `Engine.java:260` | Wrong Javadoc on `abort`. | Correct it. |
| L7 | `ItemWorkflow.java:477-478` | A convoluted `replaceAll`/`removeIf`. | `removeIf(exp::equals)`. |
| L8 | `ChangeChecker.java:98,158` | Matchers are recompiled per path and glob; the ledger is re-read per commit. | Compile once; read once. |
| L9 | `spec/AnswerParser.java:76` | Any `??` marks a reply as garbled. | Only U+FFFD, or a reply that is all `?`. |

Concurrency: every writer holds `WorkLock`, and readers rely on atomic renames. L3 and L4 are the only concurrency defects.

## 7. Diagrams

**Reference.** cathrynlavery/diagram-design stays the base. Other options were considered: C4 (useful for its levels, but it is a notation rather than a visual style) and Mermaid themes alone (not enough control for docs). Neither gives a better visual system. What is taken from diagram-design:
- one paper, ink and muted palette with a **single accent**;
- light and dark skins, a 4 px grid and hairline strokes;
- no shadows;
- serif titles, sans labels and mono annotations.

The tokens are already in `scripts/diagrams/render.py` and are now written down in `docs/guides/diagram-style.md`.

**Types used.**

| Type | Use | Status |
|---|---|---|
| Architecture | README | existing |
| Sequence | the loop | existing |
| State | item states, spec lifecycle | existing |
| **Artifact map** (data flow) | spec → cases → shard → report | new, needed for §5 |
| **Package layers** | AGENTS.md | new, needed for §2.2 |

Swimlanes, decision trees and the other ~40 types are not needed.

**Coexistence with Mermaid.**

| Where | Format | Why |
|---|---|---|
| This repo's docs (README, plan, guides) | SVG rendered by `render.py`, light and dark through `<picture>` | full control of the style; CI checks the SVGs are up to date |
| Specs and analyst output in services | **Mermaid**, inside the `.md` | gtaw parses and lints it, it diffs in PRs, and GitHub and IDEs render it |

The diagram-design rules carry over to Mermaid in a limited form:
- no colour theme, so GitHub's light and dark themes both stay readable;
- one accent through `classDef focus stroke:#bf4520,stroke-width:1.5px` on the entry node;
- `classDef external stroke-dasharray:4 4` for downstream systems;
- at most 9 nodes, sentence-case labels and verbs on edges.

**Linter fix.** Today `SpecLinter.java:134` reads the diagram type from the first line, so `%%` comments make a valid diagram fail. The linter will:
- skip `%%` lines;
- allow only those two `classDef`s;
- reject `%%{init}%%` theme overrides.

## 8. Dropped from the earlier draft (not worth the complexity)

| Dropped | Why |
|---|---|
| Splitting `GtaCli` into a `command/` package | picocli nested commands in one 469-line file are idiomatic and easy to read. |
| `gtaw doctor --naming` and `gtaw migrate-names` | gtaw already names new tests correctly, and old names keep working because the shard stores `testClass`. |
| Enums for every persisted string | Needs a state migration. Deferred (Q-R9). |
| A separate resource module | Documenting it is enough (A8). |
| macOS and Windows e2e jobs | The Windows build plus the wrapper check already cover the platform risks. |
| A "proposed cases" section in the ledger | New expectations go through the spec, its single source. Only manual cases are written in the ledger. |
| Moving `SpecLinter` to `spec.lint`, renaming `Gate`/`Profiler`/`NodeResults` | Cosmetic. |

## 9. Implementation steps

Each step is one or more commits on `claude/sweet-lovelace-w17u0a`, ends green on `mvn -B verify`, `render.py --check` and `evals/e2e/run.sh`, and can be accepted on its own.

| Step | Content | Estimated change |
|---|---|---|
| S1 | Bug fixes H1–H3, M1–M10, L1–L9, with tests | ~25 files, +800 lines (half tests) |
| S2 | `.gitignore`, `.editorconfig`, `mvnw`, enforcer, pom metadata, governance files, Dependabot, CI JDK 17 and 21, SpotBugs (findings fixed), JaCoCo report | ~15 files |
| S3 | **Format-only commit** (palantir via Spotless), then `.git-blame-ignore-revs` | 81 files, whitespace only |
| S4 | Renames (§2.3), the `ItemWorkflow` split, A1–A4 and A7, generic-ness fixes G1–G19 | ~40 files |
| S5 | Test naming: mirrored toolkit tests and allowlist, `MirroredTestNamesTest`; service mapping in Planner, BriefWriter, MavenRunner, agents, skills and templates; the `naming-conflict` decision; e2e expectations; docs and evals moves | ~50 files |
| S6 | Case ledger, INDEX.md, reports moved to `.gta/reports/` and committed, retire and confirm, `gtaw cases`, `gtaw impact`; plan §5/§9/§10 in both languages | ~25 files |
| S7 | Diagram style guide, artifact-map and package-layer diagrams, Mermaid linter rules and analyst reference | ~8 files |
| S8 | Tests up to the 90 % gate, `jacoco:check` turned on in `verify`; implementation summary in `docs/reviews/round-3-summary.md` | ~25 test files |

## 10. Open questions

The current choice is implemented now; your answer can change it.

| # | Question | Current choice | Cost of the alternative |
|---|---|---|---|
| Q-R1 | **Proposal to revise §5 "regression = affected + all P0".** I suggest this scope applies to the session's re-verification, while the CI gate keeps running **every** gta test. A full run is cheap and catches what the impact mapping misses. | Session scope = affected + P0; CI = all gta tests. | Narrowing CI to P0 saves minutes but lets non-P0 regressions merge. |
| Q-R2 | Branch floor for the core decision packages (`oracle`, `state`, `run`, `Triage`). The decision says "prioritise branches" but gives no number. | 80 % branches (and 90 % lines). | 90 % branches needs about 30 % more tests in the decision code. |
| Q-R3 | Case priority when the analyst gives none. Today's planner priority is per flow (from the spec's risk, p0–p3), but §5 defines P0–P2 per scenario. | The analyst writes `priority:P0/P1/P2` per scenario. Otherwise: confirmed or documented happy path → P0, an error branch → P1, the rest → P2. | Making `priority` mandatory adds one question per scenario. |
| Q-R4 | Should the foundation item edit the service's Surefire and Failsafe configuration to match §4.2, or only check it? | gtaw runs are explicit; `doctor` warns; the guide gives the snippet. The developer's pom is not edited. | Editing the pom touches a developer-owned file in every service. |
| Q-R5 | At session end, gtaw **commits** the report and the ledger changes, which adds one commit per session. | Commit them (otherwise they block the next item commit as pre-existing dirty files). | Leaving them uncommitted keeps history shorter, but the developer must commit them by hand. |
| Q-R6 | Nexus URL for `distributionManagement` and the wrapper default | The placeholder stays. | – |
| Q-R7 | When should multi-module Maven services be supported (G7)? | Later (a product feature). | – |
| Q-R8 | SpotBugs `EI_EXPOSE_REP*` on the persisted DTOs is excluded, not fixed. | Exclude, documented. | Fixing means getters and copies on every DTO, which is the enum/encapsulation work in Q-R9. |
| Q-R9 | Typed enums for persisted strings (statuses, verdicts, outcomes), with a `schemaVersion` 1.1 migration | Deferred. | About 30 files and a migration test. |
| Q-R10 | The id of manual cases | `<flowId>/mNN`, next to the scenario ids `sNN`. | – |
