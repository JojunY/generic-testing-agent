# Contributing to gta

Thank you for helping. The rules for changing this repository are in [AGENTS.md](AGENTS.md); they apply to people and
coding agents alike. In short:

1. Edit harness content in `core/` (and platform differences in `platforms/`), never in the generated `copilot/` or
   `opencode/`; run `scripts/generate.sh` and commit the result.
2. Every behaviour change comes with a unit test whose assertions state the documented behaviour
   ([docs/design/plan.md](docs/design/plan.md)), not a snapshot of today's output. Loop changes also need the scripted
   end-to-end run.
3. Tests follow the naming rules in [docs/guides/test-naming.md](docs/guides/test-naming.md).
4. Keep pull requests focused. A reformatting-only change goes in its own commit and into `.git-blame-ignore-revs`.

## Build and checks

```bash
./mvnw -B install                            # build, unit tests, Spotless, SpotBugs, JaCoCo gate, harness sync check
./mvnw spotless:apply                        # fix formatting
python3 scripts/diagrams/render.py --check   # docs diagrams are up to date
evals/e2e/run.sh                             # scripted end-to-end run
```

## Reporting problems

Open an issue with the gtaw command, its JSON output and `./gtaw doctor`. For security problems see
[SECURITY.md](SECURITY.md).
