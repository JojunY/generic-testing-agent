# EARS requirement patterns

Every requirement uses one of the five EARS patterns and contains SHALL (or MUST).

| Pattern | Template | Example |
|---|---|---|
| Ubiquitous | THE <system> SHALL <response>. | THE lending service SHALL store quantities as whole shares. |
| Event-driven | WHEN <trigger>, THE <system> SHALL <response>. | WHEN a client books a borrow, THE lending service SHALL respond `201` with the borrow id. |
| Unwanted behaviour | IF <condition>, THEN THE <system> SHALL <response>. | IF the inventory service does not respond within 3s, THEN THE lending service SHALL respond `503`. |
| State-driven | WHILE <state>, THE <system> SHALL <response>. | WHILE the borrow is PENDING_LOCATE, THE lending service SHALL reject a return with `409`. |
| Optional feature | WHERE <feature>, THE <system> SHALL <response>. | WHERE hard-to-borrow pricing is enabled, THE lending service SHALL apply the hard-to-borrow fee. |

Rules:

- One behaviour per requirement; split "and" into two requirements when they can fail independently.
- Name observable results (response, stored state, message, metric) — not internal calls.
- Invariants that hold for all inputs use "For any …": `For any two submissions with the same Idempotency-Key, THE lending service SHALL book one borrow.` They become property-style tests.
