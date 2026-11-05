# low-stock.transform — The nightly low-stock report
<!-- gta: flowId=low-stock.transform capability=beam-pipeline risk=6 status=draft -->

## Boundary
- In scope: the Beam transform LowStockTransform
- Out of scope: reading the stock table and writing the report (pipeline IO)
- External: none (DirectRunner)

```mermaid
flowchart LR
  S[(name, stock) pairs] --> F[LowStock: stock below 3] --> N[Names] --> R[low-stock report]
```

## Access
| Role | Operation | Ownership constraint | Source |
|---|---|---|---|
| nightly batch | build the report | none | observed |

## Requirements

### Requirement: Products to restock
<!-- gta: id=low-stock.transform/r01 ref=method:com.example.catalog.report.LowStockTransform#expand -->
WHEN the nightly report runs, THE catalog SHALL list every product with fewer than 3 in stock.

#### Scenario: an almost sold-out product is reported
<!-- gta: id=low-stock.transform/s01 source=observed hint.layer=unit hint.oracle=state priority=P1 -->
- **WHEN** the report runs over Kettle with 1 and Toaster with 12 in stock
- **THEN** the report lists `Kettle`

#### Scenario: a product with 2 left is reported
<!-- gta: id=low-stock.transform/s02 source=documented doc=__P__docs/business-rules.md quote="a product with fewer than 3 in stock is on the low-stock report" hint.layer=unit hint.oracle=state priority=P1 -->
- **WHEN** the report runs over Kettle with 1, Mixer with 2 and Toaster with 12 in stock
- **THEN** the report lists `Kettle` and `Mixer`

## Invariants

## Open questions

## Findings
