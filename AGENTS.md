# AGENTS.md — contributing to gta

These are the rules for working on this repository. They do not apply to services that use gta; those get the block in `core/rules/gta-guardrails.md`.

## Layout and sources of truth

- `core/` is the only place to edit agents, the `/gta` command, rules, skills, templates and the capability registry.
- `copilot/` and `opencode/` are **generated**. Never edit them. Run `scripts/generate.sh` and commit the result. `mvn install` fails when they are stale.
- `docs/design/plan.md` is the design. Code comments cite it as `plan §N`; keep section numbers stable when you edit it.
- Documentation, agents, skills, templates and gtaw output are English. `README.md` and `docs/design/plan.md` have a `*.zh-CN.md` copy with a language switch at the top: change both copies in the same PR. Chinese appears elsewhere only as the reply aliases gtaw accepts (`批准`, `其余默认`, `代码现状`); `RepositoryHarnessTest` enforces this.
- Explanatory diagrams are data in `scripts/diagrams/render.py` (diagram-design style, light and dark). Edit the script, run it, commit the SVGs in `docs/diagrams/`; CI runs it with `--check`. Spec diagrams stay Mermaid.
- Platform differences (tool names, permissions, models, file locations) live only in `platforms/<platform>/mapping.yaml`.
- `pom.xml` at the root is the single source of the version.

## gta-core packages

| Package | Responsibility |
|---|---|
| `engine` | `Engine` is the only public facade and routes `next`/`answer`. `SpecWorkflow` (specs, answers, approval), `ItemWorkflow` (items), `TestRuns` (every test run), `SessionManager` and `SessionEnd` do the work; `HumanPrompts` renders the question, approval and decision prompts and `EscalationWriter` the escalation records. They share one `EngineContext`; `ItemWorkflow` never calls `SessionEnd`. |
| `spec` · `plan` · `state` | spec model, parser, linter and editor · planning · item state machine and the state store |
| `oracle` · `change` · `run` · `gate` · `report` | oracle lock and shards · scope check and the commit ledger · `TestRunner` (Maven, or a fake in tests) and triage · the gate · reports |
| `profile` · `registry` · `brief` | code profiling · capability registry · agent briefs |
| `config` · `error` · `util` | `GtaPaths` (every well-known location; never hard-code paths) · `ErrorCode` · `AtomicFiles`, `Json`, `Yaml`, `Git`, `ProcessRunner`, `Ids` |

## Naming (plan §9)

| Asset | Rule |
|---|---|
| Agents | `gta-<role>`, noun role. Neutral frontmatter only: `name`, `description`, `permissionProfile`, `modelTier`, `userInvocable`, `subagents`. Sections in order: Role, Inputs, Workflow, Output contract, Rules. At most 30,000 characters. |
| Skills | `gta-<gerund>-<object>`, equal to the directory name. Frontmatter: `name` and a third-person `description` with "Use when". Sections: Quick start, Workflow, Rules, References. At most 500 lines; references one level deep. |
| Ids | flowId `<domain>.<flow-name>`, itemId `<scenarioId>#<layer>-01`, runId `r<ts>-<sha7>-<rand4>`. |
| Error codes | `PREFIX_DETAIL` in `ErrorCode`; the category sets the exit code (gate concerns exit 0), a code may override `retryable`. Every `GtaException` is thrown with a hint. |

## Engineering rules

- `gtaw` is deterministic. Anything an LLM could get wrong must not decide state: passing, locking, committing and the gate are all gtaw's.
- Only gtaw writes `.gta/work/state.json`. Every file write goes through `AtomicFiles`; state changes happen under `WorkLock` and through `WorkState.move` (never assign `status` directly).
- Every gtaw commit goes through `EngineContext.commit`, which records the SHA in the commit ledger; the scope check trusts only ledger SHAs, never commit-message trailers.
- Every gtaw write to a spec goes through `SpecWorkflow.writeSpec`, which refreshes the owned-field snapshot.
- Untrusted or merged files (shards, state) fail closed: report them, never treat them as empty.
- New behaviour needs a unit test. Changes to the loop also need the scripted e2e (`evals/e2e/run.sh`) to pass.
- Keep the Java toolkit on Java 17 language level. Keep `gta-junit` compiled against JUnit 5.14 so it works on Boot 3.5 and 4.x.
- Do not add a primitive (hook, MCP server, plugin, handoff) without updating plan §4.0 and showing why the existing ones are not enough.

## Before you push

```bash
./mvnw -B install
python3 scripts/diagrams/render.py --check
evals/e2e/run.sh
```
