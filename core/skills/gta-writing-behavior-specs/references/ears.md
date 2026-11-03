# EARS requirement patterns

Every requirement uses one of the five EARS patterns and contains SHALL (or MUST).

| Pattern | Template | Example |
|---|---|---|
| Ubiquitous | THE <system> SHALL <response>. | THE order service SHALL store amounts in minor units. |
| Event-driven | WHEN <trigger>, THE <system> SHALL <response>. | WHEN a customer submits an order, THE order service SHALL respond `201` with the order id. |
| Unwanted behaviour | IF <condition>, THEN THE <system> SHALL <response>. | IF the payment gateway does not respond within 3s, THEN THE order service SHALL respond `503`. |
| State-driven | WHILE <state>, THE <system> SHALL <response>. | WHILE the order is PENDING_PAYMENT, THE order service SHALL reject cancellation with `409`. |
| Optional feature | WHERE <feature>, THE <system> SHALL <response>. | WHERE express delivery is enabled, THE order service SHALL add the express fee. |

Rules:

- One behaviour per requirement; split "and" into two requirements when they can fail independently.
- Name observable results (response, stored state, message, metric) — not internal calls.
- Invariants that hold for all inputs use "For any …": `For any two submissions with the same Idempotency-Key, THE order service SHALL create one order.` They become property-style tests.
