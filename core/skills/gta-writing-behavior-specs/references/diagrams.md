# Diagrams (Mermaid only)

One diagram per flow, at most 40 lines, only what the tests need.

| Flow | Diagram |
|---|---|
| API, events | `sequenceDiagram` (async messages use `-)`) |
| Scheduler, batch | `flowchart TD` for steps; `stateDiagram-v2` for job/entity status |
| Workflow, state machine | `stateDiagram-v2` with every transition and error branch |
| Pipeline | `flowchart LR` of transforms, windows and the DLQ |
| Data | `erDiagram` with the entities this flow touches only |

```mermaid
sequenceDiagram
  participant C as Client
  participant API as OrderController
  participant PG as payment-gateway
  participant DB as Oracle
  C->>API: POST /api/orders
  API->>PG: authorize (timeout 3s)
  PG-->>API: approved
  API->>DB: insert order (CREATED)
  API-->>C: 201
```

State names in `stateDiagram-v2` cannot contain hyphens: write `in_review`, not `in-review`. Gliffy and images are not used: they cannot be diffed or reviewed in a PR.
