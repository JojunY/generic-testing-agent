# Intent and risk from a diff

| Diff pattern | Likely risk | Scenario to add |
|---|---|---|
| timeout/retry value changed | slower failure, duplicate calls | timeout boundary, retry count, idempotency |
| new `if` branch | untested branch, wrong condition | both sides of the condition, boundary values |
| exception handling changed | swallowed errors, wrong status | error path with exact status/state |
| query or mapping changed | wrong rows, null handling | empty, one, many, nulls |
| security annotation or filter changed | access widened | negative access tests |
| transaction boundary moved | partial writes | failure after first write |
| Batch skip/retry policy changed | silent data loss | poison record counts and destination |

State the intended behaviour from the PR description; when the description does not cover a changed behaviour, ask.
