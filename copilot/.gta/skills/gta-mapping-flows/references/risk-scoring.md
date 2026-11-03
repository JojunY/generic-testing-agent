# Risk scoring

risk = probability × impact, each 1-3. Allowed values: 1, 2, 3, 4, 6, 9.

| | Impact 1 (cosmetic) | Impact 2 (degraded) | Impact 3 (money, data, security, outage) |
|---|---|---|---|
| Probability 3 (complex, often changed, many branches) | 3 | 6 | 9 |
| Probability 2 (moderate logic) | 2 | 4 | 6 |
| Probability 1 (trivial, stable) | 1 | 2 | 3 |

Priority used by gtaw: 9 → p0, 6 → p1, 3-4 → p2, 1-2 → p3.

Examples:

- Payment authorisation with retries and timeouts: P3 × I3 = 9.
- Order creation writing to Oracle and publishing to Pub/Sub: P2 × I3 = 6.
- Read-only list endpoint with paging: P2 × I1 = 2.
- Nightly reconciliation batch: P2 × I3 = 6.

Authorisation (who may call what on whose data) always has impact 3.
