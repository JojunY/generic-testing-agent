# Access section

Broken object-level authorisation (one user reading or changing another user's or tenant's data) is the most common real API bug. Every flow with an external entry point has:

```markdown
## Access
| Role | Operation | Ownership constraint | Source |
|---|---|---|---|
| customer | create | only for themselves | observed |
| customer | read | only own orders | assumed → q02 |
| admin | read | any order in own tenant | observed |
| anonymous | any | rejected with `401` | observed |
```

Derive scenarios from it:

- one positive scenario per row,
- one negative scenario per ownership constraint (other user, other tenant),
- the status code for "exists but not yours" (`403` or `404`) is a human decision → ask.

If the code has no ownership check where one is expected, write a `behavior-suspect` finding and a high-impact question; do not record the missing check as intended behaviour.
