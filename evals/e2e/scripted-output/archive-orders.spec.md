# archive-job.archive-orders — Archive shipped orders
<!-- gta: flowId=archive-job.archive-orders capability=spring-batch risk=6 status=draft -->

## Boundary
- In scope: the Spring Batch job archiveOrders and its step archiveShipped
- Out of scope: the other order flows
- External: none

```mermaid
flowchart LR
  J[archiveOrders] --> S[archiveShipped] --> R[(OrderRepository)]
```

## Access
| Role | Operation | Ownership constraint | Source |
|---|---|---|---|
| any | Archive shipped orders | none | observed |

## Requirements

### Requirement: Archive shipped orders
<!-- gta: id=archive-job.archive-orders/r01 ref=bean:com.example.order.archive.ArchiveJobConfig#archiveOrders ref=bean:com.example.order.archive.ArchiveJobConfig#archiveShipped -->
WHEN the archive job runs, THE order service SHALL archive the SHIPPED orders and keep all others.

#### Scenario: a shipped order is archived
<!-- gta: id=archive-job.archive-orders/s01 source=observed hint.layer=component hint.oracle=state priority=P1 -->
- **WHEN** the job runs while an order is SHIPPED
- **THEN** that order's archived flag is `true`

#### Scenario: a cancelled order stays
<!-- gta: id=archive-job.archive-orders/s02 source=documented doc=__P__docs/business-rules.md quote="only SHIPPED orders are archived; CANCELLED orders stay for the refund team" hint.layer=component hint.oracle=state priority=P1 -->
- **WHEN** the job runs while an order is CANCELLED
- **THEN** that order's archived flag is `false`

## Invariants

## Open questions

## Findings
