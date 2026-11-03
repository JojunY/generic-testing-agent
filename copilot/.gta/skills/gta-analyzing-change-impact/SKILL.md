---
name: gta-analyzing-change-impact
description: Updates gta behaviour specs for a pull request - maps changed methods, bean definitions and configuration keys to affected flows, treats the PR description as documented intent, records behaviour changes as spec diffs, and proposes catching scenarios for risks the change introduces. Use when an analyze brief runs in pr mode.
---

# Analyzing change impact (PR mode, P1)

## Quick start

1. gtaw lists the affected flows (their `ref` hashes changed) and marks their specs `stale`.
2. For each affected flow, read the diff of the referenced code and the PR description.
3. Update only the requirements and scenarios whose behaviour changes; the spec diff is the behaviour-change summary reviewers see.

## Workflow

- Intent: the PR description is a `documented` source (`doc=PR#123 quote="…"`); if it is silent or ambiguous about a behaviour change, ask one question.
- Changed expectation of a characterization scenario: update the THEN to the new behaviour and keep `source=observed`; the PR comment lists it as a behaviour change for the reviewer.
- New behaviour: new requirement/scenario with sources as usual.
- Risks introduced by the change (new branch, changed timeout, new retry, removed check): add a scenario with `hint.intent=catching` (P3) describing how the change could be wrong.
- Changes no spec references are reported by the gate as `GATE_UNMAPPED_CHANGE`; mention them in a finding if they look behavioural.

## Rules

- Never edit a confirmed THEN to match the new code without a question or a documented intent.
- Keep refs current: add refs for new methods, beans and config keys the flow now depends on.

## References

- `references/intent-and-risk.md` · `references/bean-graph.md`
