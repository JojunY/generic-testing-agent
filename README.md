# gta — Generic Testing Agent

gta is a spec-first testing harness for Java/Spring services. It runs inside **GitHub Copilot** (VS Code, IntelliJ) or **OpenCode**.

You type `/gta` and it runs this loop:

1. It reads the code of one flow (a REST endpoint, a scheduled job and so on).
2. It writes a reviewable behaviour spec.
3. It asks you about anything uncertain or suspicious.
4. After you approve the spec, it writes regression and integration tests whose expected values come from the spec, not from what the code happens to do today.

Coverage is not the goal. Tests should check what the service is *supposed* to do, catch regressions, and expose defects instead of locking them in.

```
/gta order.create
  → analyst writes .gta/specs/order/create.spec.md (EARS requirements, scenarios, Mermaid diagram, questions)
  → you answer "R1: 1B 其余默认", then "批准 order.create@R2"
  → test author writes OrderCreateIT; gtaw runs it, locks the expected values and commits it
  → a test that fails against a confirmed expectation is triaged; a confirmed defect is pinned with @KnownDefect
  → session end: stability runs, the quality gate against the base branch, and a report
```

## How it is built

| Part | What it does |
|---|---|
| `gtaw` (Java CLI, `toolkit/gta-cli`) | Does every deterministic step: state, planning, test runs, failure triage, oracle lock, change check, commits, gate, report. Only `gtaw` decides that a test passed. |
| 4 agents (`core/agents`) | `gta-orchestrator` (entry point), `gta-analyst` (specs), `gta-test-author` (one test per brief), `gta-reviewer` (read-only oracle review and triage). |
| 17 skills (`core/skills`) | Testing know-how loaded by path from `.gta/skills/`: Spring APIs, WireMock, embedded dependencies without containers (Oracle, BigQuery, Pub/Sub, Kafka, JMS/MQ), Batch, schedulers, Beam and more. |
| `gta-junit` (`toolkit/gta-junit`) | `@KnownDefect` (strict expected-failure) and a listener that maps test results to scenarios. |
| `copilot/`, `opencode/` | Ready-to-install harness folders, generated from `core/` + `platforms/` by `gta-harness-gen`. |

[`plan.md`](plan.md) has the full design.

## Quick start

See [docs/quickstart.md](docs/quickstart.md). In short:

```bash
mvn -B install                      # in this repo: builds and tests the toolkit
java -jar toolkit/gta-cli/target/gta-cli.jar --root <service> init --platform copilot   # or opencode
cd <service> && ./gtaw doctor       # Windows: .\gtaw.cmd doctor
```

Then open the service in VS Code, IntelliJ or OpenCode and type `/gta`.

## Supported now (P0)

- **Supported:** Maven services on Spring Boot 4.1 (and 3.5), Java 17+, on Windows, macOS and Linux.
- **Writes tests for:** REST APIs. Other capabilities (scheduler, Batch, messaging, workflows, Beam, deployed systems) are detected and can be explained and specified now; their test authoring switches on in later phases (see `core/registry/capabilities.yaml` and plan §12).
- **No containers needed:**
  - Oracle runs against a shared test schema or H2 in Oracle mode;
  - BigQuery and GCS use fakes;
  - Kafka and JMS run embedded;
  - Pub/Sub uses an emulator downloaded from Nexus (P3).

## Repository layout

```
core/        single source of truth: agents, command, rules, skills, templates, registry
platforms/   copilot/ and opencode/ mappings (only platform differences live here)
copilot/     generated: .github/agents, .github/prompts, .vscode, .gta/skills, AGENTS.gta.md
opencode/    generated: .opencode/agents, .opencode/commands, opencode.json, .gta/skills, AGENTS.gta.md
toolkit/     gta-core, gta-cli, gta-junit, gta-harness-gen (Maven reactor; pom.xml holds the version)
wrapper/     gtaw, gtaw.cmd, gtaw-download.ps1 (download the pinned gta-cli jar from Nexus and verify its sha256)
evals/       order-service fixture with seeded defects and a scripted end-to-end run
docs/        quickstart, CI integration, adding a capability
```

## Development

```bash
mvn -B install                 # build + unit tests (also checks that copilot/ and opencode/ are in sync)
tools/generate.sh              # regenerate copilot/ and opencode/ after editing core/ or platforms/
evals/e2e/run.sh               # scripted end-to-end run against evals/fixtures/order-service
```

[AGENTS.md](AGENTS.md) has the contributor rules.
