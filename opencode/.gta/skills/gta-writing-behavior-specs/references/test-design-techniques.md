# Deriving scenarios systematically

Apply these to every requirement; each produces scenarios or questions.

| Technique | Apply to | Produces |
|---|---|---|
| Equivalence classes | each input (valid / invalid groups) | one scenario per class |
| Boundary values | numbers, sizes, dates, lengths | min, min-1, max, max+1, empty, null |
| Decision table | combinations of conditions (role × state × flag) | one scenario per meaningful row |
| State transitions | entities with a status field, jobs, workflows | every allowed edge and one forbidden edge |
| Error guessing by risk | timeouts, retries, duplicates, concurrency, time zones, DST, rounding | the failure scenarios that cost the most |

Checklist for Spring services:

- Validation errors (`400` ProblemDetail) for each invalid class
- Not found (`404`), conflict (`409`), forbidden (`403`) vs. not found for other users' resources
- Downstream timeout / `5xx` / malformed response
- Duplicate submission (idempotency key, message re-delivery)
- Transaction: what is persisted when a later step fails
- Time: midnight, month end, DST change, `Asia/Kolkata` (+05:30) offsets
- Money: rounding mode, currency scale

Do not write scenarios for behaviour the code does not have; ask whether it should.
