# AGENTS.md — contributing to gta

These are the rules for working on this repository. They do not apply to services that use gta; those get the block in `core/rules/gta-guardrails.md`.

## Layout and sources of truth

- `core/` is the only place to edit agents, the `/gta` command, rules, skills, templates and the capability registry.
- `copilot/` and `opencode/` are **generated**. Never edit them. Run `scripts/generate.sh` and commit the result. `mvn install` fails when they are stale.
- Platform differences (tool names, permissions, models, file locations) live only in `platforms/<platform>/mapping.yaml`.
- `pom.xml` at the root is the single source of the version.

## Naming (plan §9)

| Asset | Rule |
|---|---|
| Agents | `gta-<role>`, noun role. Neutral frontmatter only: `name`, `description`, `permissionProfile`, `modelTier`, `userInvocable`, `subagents`. Sections in order: Role, Inputs, Workflow, Output contract, Rules. At most 30,000 characters. |
| Skills | `gta-<gerund>-<object>`, equal to the directory name. Frontmatter: `name` and a third-person `description` with "Use when". Sections: Quick start, Workflow, Rules, References. At most 500 lines; references one level deep. |
| Ids | flowId `<domain>.<flow-name>`, itemId `<scenarioId>#<layer>-01`, runId `r<ts>-<sha7>-<rand4>`. |
| Error codes | `PREFIX_DETAIL` in `ErrorCode`, each with an exit code and a hint. |

## Engineering rules

- `gtaw` is deterministic. Anything an LLM could get wrong must not decide state: passing, locking, committing and the gate are all gtaw's.
- Only gtaw writes `.gta/work/state.json`. Every write is atomic, under `WorkLock`.
- New behaviour needs a unit test. Changes to the loop also need the scripted e2e (`evals/e2e/run.sh`) to pass.
- Keep the Java toolkit on Java 17 language level. Keep `gta-junit` compiled against JUnit 5.14 so it works on Boot 3.5 and 4.x.
- Do not add a primitive (hook, MCP server, plugin, handoff) without updating plan §4.0 and showing why the existing ones are not enough.

## Before you push

```bash
mvn -B install
evals/e2e/run.sh
```
