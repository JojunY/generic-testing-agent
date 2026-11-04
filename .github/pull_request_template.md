## What and why

<!-- One or two sentences: the problem and the change. Link the issue or the plan section. -->

## How it was checked

- [ ] `./mvnw -B install` (unit tests, Spotless, SpotBugs, coverage gate, harness sync)
- [ ] `python3 scripts/diagrams/render.py --check` (if diagrams changed)
- [ ] `evals/e2e/run.sh` (if the loop changed)

## Checklist

- [ ] Harness content edited in `core/` or `platforms/`, and `scripts/generate.sh` run
- [ ] New behaviour has a unit test that asserts the documented behaviour
- [ ] `docs/design/plan.md` and `plan.zh-CN.md` changed together (if the design changed)
