# gta in ten minutes

The short version of the design. [plan.md](plan.md) is the full reference; code comments cite its sections (`plan §N`),
so it stays one document.

## What gta is, and is not

- **Is**: a testing harness for **Java services built with Spring** (Maven or Gradle, Spring Boot 2.2 to 4.x). It runs
  inside GitHub Copilot or OpenCode and writes regression and integration tests from a behaviour spec a person approved.
- **Is not**: a tool for other languages or frameworks, a test runner for deployed environments (SIT, UAT, production),
  or a coverage generator. "Generic" means every kind of flow a Spring service has, not every stack.
- **How far it is proven**: the deterministic part (`gtaw`) has unit tests and six scripted end-to-end runs, in which a
  script plays the agents. No run with a real model on a real service is recorded in this repository yet; that is the
  pilot in [pilot.md](../guides/pilot.md). Treat every capability as unproven with models until its pilot is done.

## The one idea

A model that writes a test and also decides whether the test is right will bend the test until it is green. So gta
splits the two:

| Who | Does | Never does |
|---|---|---|
| The agents (analyst, test author, reviewer) | read code, write the spec, write one test per brief, give a verdict | decide that a test passed, change state, commit, touch production code |
| `gtaw` (a Java CLI, no model inside) | plans items from the approved spec, runs tests, checks that the spec's expected values are asserted, locks them, restores out-of-scope edits, commits, runs the gate | guess: what it cannot decide goes to a person |
| The person | answers questions, approves the spec, confirms defects | has to read every test: the spec is what they sign |

A test that fails against an approved expectation is not repaired: it is triaged, and a confirmed defect is pinned
(`@KnownDefect`) so the build stays green and the defect stays visible.

## The loop

1. `/gta` → `gtaw next` profiles the service and lists its flows (an endpoint, a listener, a job, …), riskiest first.
2. The analyst writes `.gta/specs/<domain>/<flow>.spec.md`: requirements, scenarios with WHEN/THEN, one diagram, and
   questions with a recommended answer. The person replies `OK` or picks options, then approves the revision.
3. `gtaw` turns each scenario into one item with a layer (unit, slice, component, contract) and the files it may touch.
4. The test author writes the test; `gtaw submit` runs it, checks the THEN values, locks them and commits. The reviewer
   checks the test against the spec.
5. Session end: stability re-runs, the gate against the base branch, a report. CI can run the same gate on the PR.

## The parts

| Part | Where | Why it exists |
|---|---|---|
| Capability registry | `core/registry/capabilities.yaml` | which dependencies and annotations mean which kind of flow, its layers and skills |
| Profiler | `gta-core/profile` | finds entry points in the code and names flows (`BorrowController#book` → `borrow.book`) |
| Engine | `gta-core/engine` | the `next` / `submit` / `answer` protocol and the item state machine |
| Oracle | `gta-core/oracle` | extracts asserted values from tests (Java or Gherkin) and locks them |
| Gate | `gta-core/gate` | what a PR may not break: verified scenarios, locked values, unanswered assumptions |
| Skills | `core/skills` | how to test each kind of flow; loaded by path from a brief, never auto-triggered |
| Platform mapping | `platforms/*/mapping.yaml` | the only place Copilot and OpenCode differ; `copilot/` and `opencode/` are generated |
| `gta-junit` | `toolkit/gta-junit` | maps test results to scenarios; `@KnownDefect` |

## What is specific to the first adopter

The plan was written for one organisation, and some choices come from its environment rather than from the idea. They
are defaults, not requirements:

| Assumption in the plan | If yours differs |
|---|---|
| No containers on developer machines; embedded or faked dependencies | gtaw does not care how a dependency starts, but the skills and briefs say "no containers": a Testcontainers reference would have to be added to `gta-using-embedded-dependencies` |
| Everything through a Nexus proxy; the wrapper downloads the jar from Nexus | any URL works, a `file://` path included (`scripts/try.sh`), as long as its sha256 is pinned |
| Windows machines, Oracle, BigQuery, Pub/Sub, Kafka, MQ | other stores and brokers are on the roadmap (plan §15, P5 and P6) |
| Copilot and OpenCode | another platform is a `platforms/<name>/mapping.yaml` and its templates |

## Known limits

- One language: entry-point detection, assertion scanning and the runners are Java. Another language is new code in
  the profiler, the oracle and the runner, not configuration.
- A new kind of entry point needs a registry entry, a skill **and** a branch in the profiler (see
  [adding-a-capability.md](../guides/adding-a-capability.md)).
- The oracle lock is syntactic: it sees which values a test asserts, not whether the assertion is on the right object.
  The reviewer covers that, and the reviewer is a model.
- Each scenario costs at least two agent runs (author, reviewer). What a whole service costs is measured in the pilot.

## Where to read next

| You want to | Read |
|---|---|
| try it on the sample service | README, "Try it in five minutes" |
| install it in a service | [quickstart.md](../guides/quickstart.md) |
| run the first real pilot | [pilot.md](../guides/pilot.md) |
| know why a mechanism exists | plan §3 (spec), §5 (state, lock, triage), §10 (files and the gate) |
| know how one kind of service is tested | plan §7 |
| see what is planned and what is not supported | plan §15 |
