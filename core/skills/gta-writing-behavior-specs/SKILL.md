---
name: gta-writing-behavior-specs
description: Writes gta behaviour specs in the OpenSpec-compatible format - EARS requirements, WHEN/THEN scenarios with backtick expected values, sources for every claim, an access table, one Mermaid diagram, and structured open questions with option effects. Use when an analyze brief asks for a new or revised .gta/specs/<domain>/<flow>.spec.md.
---

# Writing behaviour specs

## Quick start

Start from the template in the brief. Keep every `<!-- gta: ... -->` comment; ids are `<flowId>/r01`, `/s01`, `/i01`, `/q01`, `/f01`.

```markdown
### Requirement: Payment gateway timeout
<!-- gta: id=order.create/r02 ref=method:com.acme.order.OrderService#authorize ref=config:payment.gateway.timeout -->
IF the payment gateway does not respond within 3s, THEN THE order service SHALL respond 503 and keep the order in PENDING_PAYMENT.

#### Scenario: gateway silent for 3 seconds
<!-- gta: id=order.create/s03 source=observed priority=P1 hint.layer=component hint.oracle=state -->
- **WHEN** the gateway does not respond within 3s
- **THEN** the response status is `503` with a ProblemDetail body
- **AND** orders.status is `PENDING_PAYMENT`
```

## Workflow

1. Boundary and one diagram (see `references/diagrams.md`).
2. `## Access`: role × operation × ownership for every external entry point (`references/access-matrix.md`).
3. Requirements in EARS (`references/ears.md`), each with refs (`references/refs.md`).
4. Scenarios: derive them systematically (`references/test-design-techniques.md`), not by intuition. Each THEN names an observable result with the expected value in backticks; if no literal value exists, add `check=reviewer` to the scenario comment.
5. Priority per scenario, for the test case ledger and regression scope: `priority=P0` core path (run every time), `P1` important branch, `P2` edge case. Without it gtaw uses P1.
6. Intent (optional): `hint.intent=reproducing` for the scenario of an incident (its test must fail on the current
   code; incident briefs ask for it), `hint.intent=catching` for a risk a PR introduces. Without it the source decides.
7. Sources: `observed` (code shows it), `documented` (`doc=README.md#timeouts quote="returns 503 after 3 seconds"`), `assumed` (a guess — needs a question).
8. Questions (`## Open questions`): at most 5, highest impact first. Format:

   ```markdown
   <!-- gta: id=order.create/q01 impact=high affects=order.create/s03 -->
   1. [high impact] Should a gateway timeout be retried?
      - [ ] A) No retry (= current code)
      - [ ] B) Retry once → s03 THEN adds "the gateway is called `2` times"
   ```
9. Findings (`## Findings`): `doc-drift` (docs disagree with code) or `behavior-suspect` (code looks wrong).
9. Submit with the command in the brief and fix lint issues.

## Rules

- Never write `source=confirmed`/`defaulted`, ticks, `status`, `rev`, `refHash` or `## Answers`; gtaw writes them from the human's replies.
- Suspicious behaviour becomes a question or finding (`references/suspicious-behavior.md`), never an approved requirement.
- Generated OpenAPI (springdoc) is `observed`, not `documented`.
- Normal-impact questions need a `= current code` option: "rest default" picks it and yields a characterization test.

## References

- `references/ears.md` · `references/test-design-techniques.md` · `references/access-matrix.md`
- `references/diagrams.md` · `references/suspicious-behavior.md` · `references/refs.md` · `references/openspec-compat.md`
