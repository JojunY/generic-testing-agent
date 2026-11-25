# Changelog

All notable changes to gta. Versions follow SemVer; the version lives in the root `pom.xml`.

## [Unreleased]

### Added
- `docs/site/`: an introduction site (English and Simplified Chinese) to open from disk or to present. The title scene draws twelve scenarios of the sample services as marks that pass through three shapes (the agents, gtaw, the person) and lists them in a ledger with the verdict gtaw gave each; a replay lets a visitor give the five replies of a recorded gtaw run on the lending sample (`scripts/site/record-demo.sh` records it; with `--lang zh-CN` gtaw speaks Chinese). Static, with no dependency and no external request. Stage mode (`P`) steps through each scene with `→`, at sizes for a 1080p projector. The Chinese headings use a subset of Noto Serif SC built by `scripts/site/build-zh-heading-font.py`; `RepositoryHarnessTest` checks that it covers every heading character.
- `scripts/install-local.sh`: installs gta into a service on one machine without Nexus (the wrapper pinned to the locally built jar and its sha256); the README's Install section replaces its Quick start snippet, which left the wrapper unpinned.
- `docs/design/overview.md` (gta in ten minutes: what it is and is not, what is proven, what is specific to the first adopter, known limits) and `docs/guides/pilot.md` (the first run with a real model: checklist, what to record, what decides the outcome).
- `.github/workflows/release.yml`: a pushed tag publishes a GitHub Release with the jars and their sha256 values.
- The analyst brief lists the team's own tests that name the flow's entry class: scenarios they already assert are not repeated, and new scenarios go to the layer that can observe what they cannot (plan §7.9).
- `build.cucumber` (`auto` or `off`): `off` keeps JUnit in a service that uses Cucumber for its own acceptance tests only (plan §7.10).
- New capabilities, on by default (plan §7, §15 P4):
  - `cloud-function`: Spring Cloud Function beans, including functions deployed as Cloud Run functions, tested through the bean, the `FunctionCatalog` and the HTTP endpoint (`gta-testing-cloud-functions`);
  - Spring Cloud Stream functions: in a Cloud Stream service each `@Bean Function`/`Consumer`/`Supplier` is a `messaging` flow, tested on the test binder (`gta-testing-event-flows`, `references/cloud-stream.md`);
  - `integration`: each `@Bean IntegrationFlow` is a flow (`gta-testing-integration-flows`);
  - `resilience`: Resilience4j and Spring Retry add `gta-testing-resilience` to the service's flows;
  - `secret-manager`: tests resolve `sm@` secrets through a fake client in the bootstrap registry, never GCP (`gta-using-embedded-dependencies`, `references/secret-manager.md`).
- Cucumber in services that already run it (plan §7.10): component scenarios are written as feature files with step definitions; the Then steps are the oracle the expected-value check and the lock read; tags come from the plan; a confirmed defect is pinned with `@known-defect @known-defect:<fingerprint>`, which gta-junit's `KnownDefects` hook keeps green in a plain build while gtaw checks the fingerprint (`-Dgta.run=true`); `gta-writing-cucumber-scenarios`.
- gta-junit records Cucumber scenarios that carry a `scenario:` tag, with their feature file.
- Eval fixture `evals/fixtures/lending-service` and `evals/e2e/run-lending.sh` (seeded defects D19 to D21), in CI.
- Future phases P5 to P7 in a stated order, and a list of what gta does not support (plan §15).

### Changed
- `docs/README.md` is the index of the documentation, by reader; past review records moved to `docs/archive/reviews/`.
- Plan §7.9 states how gta chooses the layer, the strategy for services with and without tests, and why it does not use Karate.
- Easier for newcomers (plan §6, §9.4):
  - the `/gta` subcommands are shown in three tiers: `/gta` and `OK` to start, `<flow>`, `status` and `note` every day, `spec`, `pr` and `incident` when needed; `/gta service` stays accepted and is the same as `/gta`;
  - `/gta spec <flow>` (`gtaw next --mode spec`) is the new name of explain mode, whose output is a spec; `explain` stays accepted;
  - a flow can be named by its number in the list, its class (when it has one flow), `Class#method`, its source file, its endpoint (`POST /api/borrows`) or path, a topic, queue or cron in its annotation, or its migration or BPMN file. A name that fits several flows is no longer resolved to the first one: gtaw answers `USAGE_AMBIGUOUS_FLOW` with the candidates, riskiest first;
  - derived flow names drop the words that repeat the domain (`BorrowController#bookBorrow` is `borrow.book`, not `borrow.book-borrow`); a flow that already has a spec under its long name keeps it.
- The running example of the documentation, agents and skills is a securities-lending service (`borrow.book`) instead of an online shop (`order.create`).

### Fixed
- The session report lists a person's decisions on items (a confirmed defect) with the spec answers and approvals; the commit that records such a decision says "record defect confirmation", not "escalate"; the status block's gate line no longer reads as if checking nothing caused the decision (plan §5.5, §5.10).
- Status block wording (plan §5.10): the approval prompt says the spec waits for your approval, not for answers; a failed test keeps its expected and actual values on its line; a suspected defect asks "is it a product defect?" instead of repeating the reviewer's reason; at the end of a session "Needs you" says "nothing", not "nothing, continuing"; the flow prompt says a flow's number works as well as its id.
- `scripts/site/record-demo.sh --lang zh-CN` has the scripted agents write the spec and their reasons in Chinese (`scripts/site/demo/book.spec.zh-CN.md`), and the recorded commit is marked `+local` when gta has uncommitted changes.
- The status block says "1 defect found", not "1 defects found", and its gate line says how many locked scenarios the gate checked (none yet on a first session) instead of claiming every locked scenario; the report says "1 locked scenario checked" (plan §5.10, §8).
- A pinned defect that has been fixed now leaves `defect-found` (plan §5.4): once its `@KnownDefect` pin is removed and the test passes in the session-end run, the item is `passed`, its intent becomes `hardening`, and the shard and ledger are committed.
- Fixes recovered from two review branches that were never merged:
  - gate ②: a scenario counts as verified on the base only through its own lock, so a renamed scenario, or one that copies another's text, needs a test;
  - the same reviewer change in two review rounds counts as a repeat failure and goes to a person;
  - a "rest default" answer records the invariants it settles as `defaulted`, not `confirmed` (plan §3.5.3);
  - a reply that answers part of a question round keeps its changes for the analyst;
  - a failed pin attempt keeps the confirmed defect's fingerprint, which `@KnownDefect(failure)` names;
  - deleting one of two identical locked assertions is a structural change of the oracle;
  - `setRound` no longer rewrites an answer record that carries a round;
  - a shortened flow id starts with a letter (`search.search-2` stays as it is);
  - a Gradle root project with its own sources and an included module is profiled as a service;
  - `gtaw mutate` clears PIT's library folder first, so jars of an earlier version never join the classpath.
- The scope check treated any folder named `target` as build output, so an edit to a source package such as `com.acme.target` went unchecked; only a module's own `target/` is ignored now.
- A Gradle `<failure>` of a JVM error (`ExceptionInInitializerError`, `NoClassDefFoundError`) was read as an assertion failure; it is an error now, as with Surefire.
- A plain HTML comment that mentions `gta:` (`<!-- TODO: ask about gta: retries -->`) crashed the spec parser; it is text now. A gta comment that is never closed is reported as `POLICY_SPEC_INVALID` with a hint.
- Item statuses, spec section names and reply letters no longer depend on the JVM's default locale (a Turkish locale broke `state.json`).
- `gtaw run` exits 40 (`BUILD_COMPILE_ERROR`) when the tests do not compile, not 10. `answer --file` with a missing file and `gate waive` with an unreadable `.gta/waivers.json` are the person's to fix (exit 20 and 21), not internal errors.
- `gtaw` reads the wrapper properties as `gtaw.cmd` and `gtaw doctor` do: blanks around `=` and CRLF line ends are allowed.
- The oracle shard schema accepts the `catching` intent gtaw writes; the status block names the `oracle-defect` and `environment` verdicts in `output.language`.
- Skill references no longer tell agents to mark scenarios `hint.layer=system` or to deploy workflows: deployed environments are out of scope (plan §7.7).
- `qualityGates.mutation.mode=enforced` had no effect: the gate now runs PIT on the changed lines in enforced mode and fails below `minStrength` (`GATE_MUTATION_BELOW_THRESHOLD`, plan §10.5 ⑥). Advisory mode stays with `gtaw mutate --changed`.
- An item run again unchanged after a person's decision (for example "run it again" after an environment fix) committed its lock and ledger rows without its test: gtaw now remembers the files the author wrote until the item is committed.
- `gtaw.cmd` stops with `CONFIG_NOT_INITIALIZED` when the wrapper properties have no `version`, as `gtaw` does.
- `config.v1.schema.json` states the caps gtaw applies (`maxAttemptsPerItem` 3, `maxChecksPerItem` 20, `maxItemsPerSession` 10, `stabilityRepeat` at least 2), so an editor flags a value gtaw would change.
- `mvnw.cmd` is stored with LF in git, as `.gitattributes` says (checked out with CRLF).
- Fixes left on review branches that were never merged:
  - brief names, author tokens and the numbers in gtaw's messages are written with ASCII digits whatever the JVM's locale (an Arabic or Persian default wrote `۰۰۰۷`);
  - `gtaw doctor` accepts a root project with sources of its own that includes modules, as profiling already does;
  - plan §10.4 shows the `build.tool` default (`auto`) and `build.gradle`.
- Review round 8:
  - the scope check took the spec and oracle files of a domain named `target` (`.gta/specs/target/…`) for build output and left them unchecked;
  - on Gradle, two methods whose display names agree up to a `(` (`refund (s01)`, `refund (s02)`) gave both results to the first scenario; such a case is left untagged now;
  - service mode parked the decision about a domain's seed item once per flow: the copy left after the person answered no longer cancels the passed seed or blocks every answer, it is dropped;
  - a test author's question dropped by a switch of flow or mode is asked again when the item comes back, not lost;
  - `gtaw answer` and `gtaw note` take a reply that starts with `-` (a list) as words, not as an unknown option; a reply file that is not UTF-8 is the person's to fix (exit 20), not an internal error;
  - git's warnings and hints on stderr no longer end up in the output gtaw parses (a `hint:` before `git status` hid a changed file from the scope check);
  - an `application.properties` with a Latin-1 byte, which Spring Boot reads as ISO-8859-1, is no longer skipped as a whole;
  - a spec that is not UTF-8 is reported as `POLICY_SPEC_INVALID`, not as an unknown flow;
  - `produces = {MediaType.TEXT_EVENT_STREAM_VALUE}` is a server-sent event stream, as without the braces;
  - `gtaw mutate` clears PIT's report folder first: a run without mutants no longer reads the survivors of the run before;
  - briefs show free text in single quotes (`--question '…'`, `--reason '…'`), as the agents' rules say.
- Review round 7:
  - submit re-checks the locks of the pinned (`defect-found`) items that share the test class, not only the passed ones: changing a pinned scenario's expected value to the actual one is `POLICY_ORACLE_MODIFIED` (plan §5.3);
  - a confirmed defect starts its pinning with a new attempt and check budget, as a retry does;
  - on Gradle, an exception without a message is fingerprinted with no message, like gta-junit does, so its `@KnownDefect` pin matches;
  - the failure fingerprint spells out its word edges instead of `\b`, whose meaning changed in JDK 19, so gtaw and a service on another JDK mask a number next to a non-ASCII letter (`Auftragé20240001234ö`) the same way;
  - PR mode reads the config keys a branch changed from the commit it left the base at, not the base's tip: a key changed on the base since then no longer flags scenarios;
  - `gtaw doctor` accepts a `build.module` starting with `./`.
  - with `core.autocrlf` on (Windows), git's "LF will be replaced by CRLF" warning no longer ends up in the paths gtaw reads from `git status`, where it hid a changed file from the scope check.
- Review round 6, second pass (fixes not covered above):
  - oracle lock and gate:
    - REST Assured expectations after `then()` (`then().body("status", equalTo("PENDING"))`) are expected values and locked, not request inputs, so a correct test no longer fails with `POLICY_ORACLE_MISMATCH`;
    - a value locks a statement only as a whole number: a THEN of `1` no longer locks `hasSize(10)` or `isEqualTo(-100)`;
    - `gtaw gate` reports an unreadable shard of a spec changed in the PR as `GATE_SHARD_UNREADABLE` instead of aborting;
  - engine:
    - an item that was dispatched before (it has a baseline) is dispatched to its author again instead of being adopted as an existing test;
  - runs:
    - an inherited `JAVA_TOOL_OPTIONS` holding only Spring options (`-Dspring.profiles.active=uat`) is removed from every run, PIT's included, instead of reaching the tests unchanged;
    - Maven runs pass `-Dsurefire.rerunFailingTestsCount=0`: a service's rerun setting no longer turns a flaky failure into a pass;
    - `gtaw mutate --changed` mutates changed lines in nested and anonymous classes (PIT is given `Outer$*` too);
    - `gtaw run` exits non-zero when the run timed out or the build failed without test reports;
  - specs and configuration:
    - `OK 2: retry twice` records the own answer for question 2 instead of its recommended option;
    - a question whose `gta:` comment runs over two lines can be ticked;
    - `build.module` may start with `./`;
    - a rename git reports in the work-tree column (after `git add -N`) counts its source as a change;
  - CLI and wrapper:
    - `gtaw env` says deployed environments are out of scope instead of "arrive in P4"; the Beam skill no longer points to `gtaw env` for Dataflow runs.
- Review round 6, follow-up:
  - oracle lock and gate:
    - an assertion is locked with its subject (`assertThat(r.status()).isEqualTo(503)`) and with what a verification expects (`verify(producer).send("orders", payload)`, RestAssured `statusCode(201).body(...)`), so asserting on another object or changing the verified arguments is reported. Shards locked before hold a fingerprint without them: the gate reports them once; relock them (`./gtaw relock`);
    - in a Scenario Outline only the Examples columns a Then step names (`<fee>`) are expected values; the other columns are watched inputs;
    - the watched inputs include the helpers a test calls (one level), a class-level `@Sql` and fixtures named by constants (plan §5.3);
  - engine:
    - a review or triage round that spans sessions while the files changed (the developer's edits or commits) runs the test again on the current tree first, instead of reverting those edits or escalating the commits;
    - a commit made outside gtaw during spec analysis ends the analyst's round instead of failing every resubmit until the flow had to be aborted; `./gtaw next` issues a new brief;
    - the scope check fails closed when HEAD no longer contains the baseline commit (a reset or a branch switch), instead of seeing no change;
    - running `./gtaw next --mode pr` again keeps the flows an earlier run made stale and did not finish;
  - runs and profiling:
    - `mutate --changed` and the can-fail check count mutants of nested classes and lambdas (`Outer$Inner`, `lambda$create$0`) as their source class and method;
    - a Gradle root with only `settings.gradle(.kts)` is read by `doctor` too;
  - CLI and wrapper:
    - `gtaw doctor` warns when git records `gtaw` without the executable bit (an `init` on Windows) and gives the command to fix it;
    - `init`/`upgrade` and `doctor` read `gta-wrapper.properties` as the wrappers do, so a backslash in a value is kept;
    - `gtaw-download.ps1` encodes Nexus credentials as UTF-8, like curl, and uses the jar a parallel first run placed in the cache;
  - plan §13 states that `.gta/work/**` cannot be restored by the change check.
- Review round 6:
  - oracle lock and gate:
    - `assertTrue(order.isPaid())` and `assertFalse(...)` are locked for a THEN of `true`/`false` (only `isTrue()` was);
    - a number inside another number no longer satisfies a THEN value: `-100`, `1000` and `100.5` do not assert `100`;
    - the examples of a Cucumber Scenario Outline are part of its locked statements, so changing an expected value in them changes the fingerprint;
    - submit re-checks the locks of the passed items that share the test class (plan §5.3 "shared files"): weakening another scenario's locked assertion is `POLICY_ORACLE_MODIFIED`, not committed;
  - engine:
    - the seed item `<domain>#foundation-01` is shared by the flows of a domain: a second flow no longer resets it (a passed seed was re-run, a seed waiting for a person was written again without them) or adds a second plan entry;
    - a confirmed defect of a committed test is pinned by the author instead of being adopted and re-run unpinned;
  - runs and profiling:
    - Gradle writes `<failure>` for every exception: a failure of an exception type (`NullPointerException` in setup) is an error, not `TEST_ASSERTION_FAILED`;
    - `gtaw mutate --changed` counts the lines changed since the branch left the base (not lines only the base changed), all lines of a file not yet added to git, and reports no mutant instead of failing when PIT generates none (`--failWhenNoMutations=false`);
    - a Gradle build whose root has only `settings.gradle(.kts)` can be profiled with `build.module`;
  - CLI, agents and wrapper:
    - gtaw no longer expands an argument starting with `@` into a file's contents (`./gtaw note '@team'`);
    - `note --file` drops a UTF-8 BOM like `answer --file`;
    - `gtaw init --ci` works without `--platform` on an installed service, as documented;
    - the orchestrator and test author pass every free text (`--reason`, `--question`, `--recommend`, `answer`, `--target`) in single quotes, so the shell does not run a backtick in a reviewer's reason;
    - `gtaw.cmd upgrade` no longer reads its next line from the rewritten `gtaw.cmd` (java and `exit /b` are one line); `gtaw-download.ps1` enables TLS 1.2 on Windows PowerShell 5.1.
- Review round 5, follow-up:
  - the can-fail check and `gtaw mutate` count only mutants a test ran against: `NON_VIABLE`, `RUN_ERROR` and `NOT_STARTED` mutants no longer read as survivors, which made a good test look like one that passes whatever the code does;
  - an assertion of a test-class constant (`isEqualTo(MSG)`) is locked by the constant's value, so weakening it or changing the constant is reported (shards locked before report it once; relock them);
  - a review verdict on a round issued without a record of the reviewed files is refused instead of being taken unchecked; `./gtaw next` issues the round again;
  - the gate's `GATE_SCENARIOS_UNLOCKED` concern says what it checks: the unchanged locked scenarios of a spec that is not approved are still checked;
  - `gtaw.cmd` returned exit code 0 for every wrapper error (not configured, download failed, checksum mismatch), so a caller took the failure for success; it now returns the category's code (config 21, environment 30), and the Windows CI job checks it.
- Review round 5 (four review passes, merged):
  - oracle lock and gate:
    - the lock covers assertions of a value with spaces (`` `Order not found` ``) and of a value written by a name (`status().isNotFound()`, `HttpStatus.SERVICE_UNAVAILABLE`, `isTrue()`), so weakening or deleting them changes the fingerprint and the gate reports it. Shards locked before hold a fingerprint without those assertions: the gate reports them once; relock them (`./gtaw relock`);
    - a THEN value first asserted after the lock was taken is locked too;
    - a `classpath:` fixture (`@Sql("classpath:fixtures/orders.sql")`) is watched as an input of its scenario;
    - nothing may change between the run under triage or oracle review and the verdict: an accept commits exactly the test that ran, and a reviewer's own edits are reverted and the verdict is not used;
    - a file an agent's commit touched is reported unrestorable even when it is also dirty;
  - engine:
    - a new session after a clean end gives open items a fresh scope baseline, and so does the session end when it sends an item back (can-fail, unstable), so the developer's commits, pulls and edits are not stopped as "commits made outside gtaw" or reverted;
    - a new session clears the briefs and review rounds of the old one (their tokens);
    - service mode no longer stalls when the run pauses after too many items in a row needed a person: the parked decisions are asked, and the paused flow goes on after them;
    - a reproducing test that confirms the reported defect no longer counts toward `budgets.maxConsecutiveFailures`;
    - the observation auto-correction commits the corrected spec, so the shard and the committed spec agree;
    - gtaw no longer commits the developer's uncommitted edits in a test class it shares with them (naming conflict answered A);
    - after `abort`, approving an unchanged in-review spec no longer bounces back to the analyst;
    - `gtaw gate`, `run` and `mutate --changed` take the work lock; lessons are recorded once per kind;
  - specs and answers:
    - "1: B 2: C" and "2: B rest default" are picks, not own answers;
    - a person's reply with `-->`, `\` or `"` is stored intact in the spec's answer and approval records;
    - a new question ticked by the analyst is refused as an owned field; question ids used twice are refused; a scenario without an id is a lint issue, not a crash;
  - runs and profiling:
    - Gradle stack frames (`at app//com.acme.OrderService.create(`) are read, and the Gradle Test task never loads from the build cache, so stability repeats really run;
    - two Cucumber scenarios with the same name are no longer matched by guesswork: a report case that could be either is left untagged instead of giving one scenario's result to the other;
    - the Spring Boot version of an imported `spring-boot-dependencies` BOM is resolved from its `${…}` property;
    - Flyway, Liquibase and BPMN files whose names do not start with a letter (`V1__2fa.sql`, a description in Chinese) get a valid, unique flow id;
    - class and method request mappings are joined with a slash (`/api/orders` + `{id}`); Gradle's `includeBuild` and multi-line `include(...)` are read right; `doctor` accepts a nested Maven module;
    - the repository's own `.github/workflows` no longer make a service look like a workflow engine, and file detection skips `.git` and `target` on Windows too;
    - git paths with non-ASCII characters are read as they are (`core.quotePath=false`); `file:` refs and managed files ignore CRLF line endings, so a Windows checkout (`core.autocrlf`) is neither a stale ref nor a local edit;
    - files gtaw writes keep their permissions (a new file is `rw-r--r--`, not owner-only);
  - CLI, install and wrapper:
    - an agent can no longer run `./gtaw next --takeover` without a person (Copilot auto-approve, OpenCode rules); OpenCode agents get the whole gtaw permission list (the YAML alias in `mapping.yaml` is resolved); the orchestrator passes a person's words in single quotes;
    - `answer --file` accepts a UTF-8 BOM (Notepad, PowerShell 5.1); the usage-error hint names the command that failed, not `--root`;
    - `init`/`upgrade` keep a team setting whose shape differs from gta's (`"bash": "ask"` in `opencode.json`) and list gta's keys to add by hand;
    - `gtaw` escapes `\` and `"` in the Nexus credentials it passes to curl and reads the jar's sha256 from stdin; `gtaw.cmd` never runs an inherited `GTA_JAR` and exits 21 for a configuration error like `gtaw`; `gtaw-download.ps1` prints valid JSON when the wrapper is not configured or its properties file is missing; `scripts/install-local.sh` works from a path with a space, `#` or `%` and shows an `init` error.
- Retiring a scenario no longer deletes a test class that has no method of that scenario.
- The scripted e2e adds gta-junit to the project's dependencies, not to its `<dependencyManagement>`.
- Review round 4:
  - a negative expected value (`isEqualTo(-1)`) is one token, so it satisfies a THEN of `-1` and no longer a THEN of `1`;
  - lint refuses a requirement, scenario or invariant id used twice (plan items and the gate key on it);
  - the observation auto-correction rewrites the literal in THEN clauses only, not in the WHEN inputs, and the corrected spec is no longer reverted by the item's next scope check;
  - after three items in a row need a person, gtaw asks the waiting decision first instead of pausing for good; any answer to a decision resets the counter;
  - the session gate ignores the unstable tests of an earlier session's verify run;
  - a failing parameterized invocation is never rewritten to `known-defect` because another invocation of the same method was pinned;
  - build output that is not UTF-8 no longer aborts a run as "tool unavailable";
  - one unreadable `application*` file no longer empties the `config:` refs, and the base file wins over profile files;
  - the wrapper caches the jar by version and sha256, so changing the pinned URL and sha256 downloads the new jar on the first run.
- Review round 2:
  - service mode no longer parks a test author's question: the waiting item kept its scope baseline, so other flows' changes in between read as out of scope when it resumed;
  - an own answer may say "approve" ("2: a manager must approve refunds"), and one written after a comma ("OK, 2: a manager signs off") is read as an own answer, not as option A;
  - the intent and priority tags no longer land inside the method body when the scenario tag shares a line with the declaration;
  - items planned by an earlier version take the scenario's priority instead of keeping a risk-based one;
  - a non-service mode drops parked decisions instead of listing them as waiting forever;
  - `gtaw run --tags` refuses only the tag `system`, not a flow or scenario named after it;
  - a scenario's priority has one source (`Spec.Scenario.priority`), and the engine moves between flows on a typed dispatch outcome instead of matching message text.
- OpenCode's gta agents allowed every `./gtaw` command, so `gate waive`, `relock` or `abort` ran without a person confirming them (Copilot asks). The agents now allow the same subcommands as Copilot's auto-approve list and ask for every other gtaw command, `note` included, so an agent cannot record a note as if a person had said it.
- A skipped test (`@Disabled`, a failed assumption) was not a failure, so it went to review as if it had passed: an author could disable the test to get it through. It is now refused (`POLICY_TEST_SKIPPED`) and goes back to the author.
- `OK, but change s02` on an approval prompt approved the spec, and on a decision took the recommended option: only a bare `OK` takes a recommendation now, anything more is a change request (approval) or is refused (decision).
- Gradle's JUnit XML names testcases by display name and puts the exception type in front of the message; the report parser now maps such cases to gta-junit's results and fingerprints the bare message, so `@KnownDefect(failure=...)` matches.
- A THEN value of `true` or `false` could never be satisfied: the oracle scanner ignored boolean literals. `isEqualTo(true)`, `isTrue()`, `isFalse()`, `assertTrue` and `assertFalse` now assert them (found by the Spring Batch eval).

### Added
- Adversarial e2e (`evals/e2e/run-adversarial.sh`, CI `e2e (adversarial)`, plan §14 metrics 3 and 4): a scripted cheating author tries eight attacks on the items of one flow, and the run fails unless gtaw refuses every one before anything is committed.
- Mutation testing on Gradle builds: `gtaw mutate --changed` and the can-fail check run PIT on the test runtime classpath that a gtaw init script lists (`.gta/work/pit/gta-pit.gradle`); PIT is resolved through the service's own repositories, and the build script never changes. The Gradle e2e kills a mutant of a changed line.
- Server-sent event streams are `websocket` flows (`SseEmitter`, `ServerSentEvent`, `produces = text/event-stream`), so their briefs load the streaming-endpoints skill instead of the REST one. `order-service` gains an SSE quote ladder (seeded D18) that the main e2e takes to a pinned defect, and `references/sse.md` shows the verified `MockMvc` reading of a completed stream.
- `websocket` eval (P4 preview): `@MessageMapping`/`@SubscribeMapping` handlers of a `@Controller` and `WebSocketHandler` implementations are `websocket` flows (the trigger names the STOMP destination). `order-service` gains a STOMP quote endpoint (seeded D17) and the main e2e, opted into P4, takes it to a pinned defect. The `gta-testing-streaming-endpoints` example is now the verified Boot 4 test: `JacksonJsonMessageConverter`, a real `StompFrameHandler`, and a resend loop, because the simple broker sends no RECEIPT for a SUBSCRIBE.
- One tag vocabulary (plan §9.5, P4): before every run gtaw gives the item's test method exactly one intent tag (`characterization`, `hardening`, `reproducing`, `catching`) and one priority tag (`p0`, `p1`, `p2`, from the scenario's `priority`, P1 when absent), replacing wrong or duplicate ones; the brief names them. `@KnownDefect` carries the `known-defect` tag. `gtaw run --tags "<JUnit tag expression>"` selects gta tests (`--layer` stays as an alias), and `gtaw impact` prints its regression scope as `groups`, a ready `-Dgroups` expression. Plan items take their priority from the scenario instead of the spec's risk.
- Service mode (plan §6, P4): `gtaw next --mode service` (`/gta service`, or `OK` on the flow list `/gta` shows when nothing is in progress) queues every detected flow, riskiest first. A flow that waits for a person does not stop the others: its decision is parked and gta goes on with the next flow; parked decisions are asked oldest first once no queued flow can go on without the person, and the status block names the flows still waiting. Explain-only flows and flows whose remaining items are blocked move on to the next flow. The PR queue is now `queue` in the state file (`prQueue` is still read).
- Authors ask instead of guessing (plan §5.10, P4): `gtaw submit --item <id> --token <t> --question "<q>" [--recommend "<a>"]` shows the question to the person as an answer node (in the status block, with the recommendation), spends the brief's token and keeps the item with its author. The reply is the answer in the person's own words, or `OK` for the recommendation; the author's next brief lists it under "Answers from the person". At most 3 questions per item.
- `/gta note <text>` (`gtaw note`, plan §5.10, P4): a person tells gta what it cannot read from the code. gtaw appends the note with date and author to the tracked `.gta/notes.md` and commits it; analyst briefs list the notes, which a scenario cites as `source=documented doc=.gta/notes.md`.
- The status block (plan §5.10, P4): every node that reaches a person (`answer`, `escalate`, `done`) carries `display`, one block in five fixed parts: state and progress (spec state, tests passed/total, defects found), what happened, what was checked, what needs you, and what comes next. gtaw writes it from facts it recorded along the way (spec written or approved, answers recorded, tests passed or failed, re-runs, the reviewer's verdicts, pinned defects, the gate) and shows each fact once, in `output.language`. When no flow is selected it lists the flows and recommends working on all of them (`onOk`, service mode). The orchestrator shows the block verbatim and no longer writes its own summaries.
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
- `safety.protectedHosts` is removed (`POLICY_PROTECTED_HOST`, the `protected-hosts` doctor check, the Oracle `testUrl` check). A host list only protects once someone fills it in, and a string match cannot see an address built at run time. What stays: every run drops the developer's Spring profile, Spring configuration and cloud credentials, and the test author's and reviewer's rules say tests run locally only and never use a URL or profile that names prod, sit or uat. A `.gta/config.json` that still has a `safety` section is now refused (`CONFIG_INVALID`): delete the section.
- Copilot and OpenCode are supported equally (plan v11): the plan no longer calls OpenCode secondary; OpenCode also allows `.\gtaw <command>` (PowerShell), `gtaw doctor` explains OpenCode's approvals as it does Copilot's, and the e2e installs both (main and multi-module: Copilot; catalog and Gradle: OpenCode) and checks each platform's agents, command and approvals.
- P5 trimmed (plan §15, v11): hooks, plugin distribution, record-replay and native-image are dropped; opt-in SIT/UAT tests (design recorded in §7.7), MCP wrapping, SARIF, performance tests, parallel authoring and other languages stay deferred until a team needs them.
- P4 is the current phase: `websocket` flows write tests by default, without `"phase": "P4"`; the main e2e runs `quote.request-quote` without a `phase` setting.
- Scope: gta does not test deployed environments (SIT, UAT, production), now or later, because teams test SIT and UAT themselves. The `deployed-runtime` capability, the `gta-testing-deployed-systems` skill, `gtaw env`, the Karate system layer and BigQuery temporary datasets are removed from the roadmap (plan §7.7, §15). `gtaw run --layer system` says so.
- P3 is the current phase: WebFlux, gRPC, GraphQL, workflows and Beam write tests by default, without `"phase": "P3"`. Deployed-system tests (P4) stay explain-only; `"phase": "P4"` previews them. The catalog e2e runs without a `phase` setting, and its CI job is now `e2e (catalog)`.
- CI: the four scripted e2e runs (main, multi-module, catalog, Gradle) run as parallel jobs instead of one after another in `build (21)`, with the same scripts and coverage, so a PR waits about 17 minutes instead of about 41. A newer push to the same PR cancels the run it replaces; runs on `main` are never cancelled.
- Roadmap (plan §15): every mainstream Spring service type is in scope, ordered by how common it is. Multi-module, messaging and scheduled jobs move to P1; security and caching join P2; WebFlux, Gradle, gRPC and GraphQL are P3; WebSocket/SSE joins P4.
- Briefs ask for tests at the service's own Java level (from `maven.compiler.release`, `java.version`, …) instead of "Java 17+", so a Java 17 service gets no newer syntax or APIs.
- `gtaw doctor` fails a service on a Java older than 17 (`service-java`); CI also builds on JDK 25.
- `docs/guides/quickstart.zh-CN.md`: a Chinese copy of the quickstart.

## [0.1.0] — 2026-11-05 (P0 preview: REST APIs only)

The first release. Other capabilities are explain-only until their phase (see the capability registry). Findings from the P0a platform validation go into 0.1.1.

### Fixed (code review, round 3; see docs/archive/reviews/round-3-plan.md §6.3)
- Git failures (dubious ownership, a stale `index.lock`, an unknown base) fail closed instead of reading as "nothing changed"; git always runs with `LC_ALL=C`.
- A failed item commit unstages its files and undoes gtaw's own shard write.
- Parameterized and repeated tests keep their scenario tags; unreadable Surefire reports fail the run.
- Timed-out processes keep their output; stale reports that cannot be deleted fail the run.
- An out-of-scope file that was only staged is removed when it is restored.
- Oracle shard keys are validated; error codes from older state files no longer crash.
- The wrapper never puts Nexus credentials on a command line, and reports download failures as JSON.

### Fixed (round 4; see docs/archive/reviews/round-4-plan.md)
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

### Fixed (code review, round 2; see docs/archive/reviews/round-2-remediation.md)
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
