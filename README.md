# gta — Generic Testing Agent

**English** | [简体中文](README.zh-CN.md)

gta is a spec-first testing harness for Java/Spring services. It runs inside **GitHub Copilot** (VS Code, IntelliJ) or **OpenCode**.

> **Status:** `gtaw`, the deterministic part, is covered by unit tests and six scripted end-to-end runs in which a script plays the agents. A run with a real model on a real service is not recorded here yet: start with the [pilot guide](docs/guides/pilot.md). New here? Open [the introduction](docs/site/index.html) in a browser (a local file, no server needed), or read [gta in ten minutes](docs/design/overview.md); [docs/README.md](docs/README.md) says which document is for whom.

You type `/gta` and it runs this loop:

1. It reads the code of one flow (a REST endpoint, a scheduled job and so on).
2. It writes a reviewable behaviour spec.
3. It asks you about anything uncertain or suspicious.
4. After you approve the spec, it writes regression and integration tests whose expected values come from the spec, not from what the code happens to do today.

Coverage is not the goal. Tests should check what the service is *supposed* to do, catch regressions, and expose defects instead of locking them in.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/diagrams/architecture-dark.svg">
  <img alt="How gta fits together: core/ and platforms/ generate copilot/ and opencode/; in a service, the orchestrator relays between the developer, subagents and gtaw, which runs tests, locks oracles and commits." src="docs/diagrams/architecture-light.svg">
</picture>

```
/gta borrow.book
  → analyst writes .gta/specs/borrow/book.spec.md (EARS requirements, scenarios, Mermaid diagram, questions)
  → each question shows a recommended answer: you reply "OK" (or "OK, 2B"), then "OK" again to approve R2
  → test author writes BorrowControllerIT; gtaw runs it, locks the expected values and commits it
  → a test that fails against a confirmed expectation is triaged; a confirmed defect is pinned with @KnownDefect
  → session end: stability runs, the quality gate against the base branch, and a report
```

## How it is built

| Part | What it does |
|---|---|
| `gtaw` (Java CLI, `toolkit/gta-cli`) | Does every deterministic step: state, planning, test runs, failure triage, oracle lock, change check, commits, gate, report. Only `gtaw` decides that a test passed. |
| 4 agents (`core/agents`) | `gta-orchestrator` (entry point), `gta-analyst` (specs), `gta-test-author` (one test per brief), `gta-reviewer` (read-only oracle review and triage). |
| 24 skills (`core/skills`) | Testing know-how loaded by path from `.gta/skills/`: Spring APIs, WireMock, embedded dependencies without containers (Oracle, BigQuery, Pub/Sub, Kafka, JMS/MQ), Batch, schedulers, Beam and more. |
| `gta-junit` (`toolkit/gta-junit`) | `@KnownDefect` (strict expected-failure) and a listener that maps test results to scenarios. |
| `copilot/`, `opencode/` | Ready-to-install harness folders, generated from `core/` + `platforms/` by `gta-harness-gen`. |

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/diagrams/loop-dark.svg">
  <img alt="One step of the loop: the orchestrator asks gtaw for the next node, hands it to a subagent or the developer, and passes check, submit and answer back to gtaw." src="docs/diagrams/loop-light.svg">
</picture>

[`docs/design/plan.md`](docs/design/plan.md) has the full design ([简体中文](docs/design/plan.zh-CN.md)).

## Install

There is one gta; you choose where its jar comes from. Both ways install the same harness into your own service.

| | On your machine (no Nexus) | For a team |
|---|---|---|
| For | one developer evaluating or using gta | everyone who works on the service, and CI |
| The jar comes from | this checkout, built locally | a shared URL (Nexus, or a GitHub Release) |
| How | `scripts/install-local.sh <service> [copilot\|opencode]` | [docs/guides/quickstart.md](docs/guides/quickstart.md), steps 1 to 3 |

```bash
scripts/install-local.sh path/to/your-service          # builds gta once, installs it, runs ./gtaw doctor
```

Then open the service in VS Code, IntelliJ or OpenCode and type `/gta`.

A local install pins the wrapper to a path on your machine and takes `gta-junit` from your `~/.m2`, so teammates and CI cannot use it. To move to the team install, run `init` again with the shared URL (quickstart, step 2): nothing else changes.

## Supported now

- **Supported:** Maven and Gradle services on Spring Boot 4.1 and 3.5 (2.2 to 2.7 too), Java 17+ (a few on Java 8 or 11 too), on Windows, macOS and Linux. gtaw itself needs JDK 17+.
- **Writes tests for:** REST APIs (with security rules and caches), message listeners (Kafka, Pub/Sub, JMS/MQ, RabbitMQ, SQS), scheduled jobs (`@Scheduled`, Quartz), Spring Batch jobs, Flyway/Liquibase migrations, WebFlux endpoints, gRPC services, GraphQL handlers, workflows (Temporal, Spring Statemachine, BPMN) and Beam transforms. WebSocket, STOMP and SSE endpoints, Spring Cloud Functions (including Cloud Run functions), Spring Cloud Stream functions, Spring Integration flows, with Resilience4j and Spring Retry behaviour and Secret Manager faked (P4). In a service that already runs Cucumber, scenarios are written as feature files (plan §7.10). gta only runs tests locally: it never tests deployed environments (SIT, UAT, production), every run drops your shell's Spring profile and cloud credentials, and the agents' rules say tests use local dependencies only (plan §7.7, §13).
- **No containers needed:**
  - Oracle runs against a shared test schema or H2 in Oracle mode;
  - BigQuery and GCS use fakes;
  - Kafka and JMS run embedded;
  - Pub/Sub uses an emulator downloaded from Nexus (P3).

## Repository layout

```
core/        source of truth for harness content: agents, command, rules, skills, templates, schemas, registry
platforms/   copilot/ and opencode/ mappings (only platform differences live here)
copilot/     GENERATED from core/ + platforms/copilot (committed so teams can pick it): never edit by hand
opencode/    GENERATED from core/ + platforms/opencode (committed so teams can pick it): never edit by hand
toolkit/     Java sources: gta-core, gta-cli, gta-junit, gta-harness-gen (Maven reactor; pom.xml holds the version)
wrapper/     files installed into services: gtaw, gtaw.cmd, gtaw-download.ps1 (pinned jar from Nexus, sha256-verified)
scripts/     install-local.sh (install into a service without Nexus), generate.sh (platform folders), diagrams/render.py (docs/diagrams), site/record-demo.sh (records the replay of docs/site)
evals/       order-service fixture with seeded defects and a scripted end-to-end run
docs/        guides/ (quickstart, pilot, CI integration, test naming, diagram style, adding a capability), design/ (overview: start here; plan: the full design), archive/ (past review records), diagrams/, site/ (the introduction site, static); README.md is the index
```

## Development

```bash
mvn -B install                       # build + unit tests (also checks that copilot/ and opencode/ are in sync)
scripts/generate.sh                  # regenerate copilot/ and opencode/ after editing core/ or platforms/
scripts/diagrams/render.py           # regenerate docs/diagrams after editing a diagram (--check in CI)
evals/e2e/run.sh                     # scripted end-to-end run against evals/fixtures/order-service
```

[AGENTS.md](AGENTS.md) has the contributor rules.
