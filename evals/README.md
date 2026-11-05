# Evals

## Fixture: `fixtures/order-service`

A small Spring Boot 4.1 service: a REST API to create, get, cancel and ship orders with a volume discount, a scheduled shipping job and a loyalty listener on an application event. `docs/business-rules.md` states two rules the code breaks. It has five seeded defects, each marked `SEEDED DEFECT` in the code:

| Id | Where | Defect | A correct gta run… |
|---|---|---|---|
| D1 | `PricingService#discount` | `>` instead of `>=`: an order of exactly the threshold gets no discount, although the Javadoc says "at least" | asks about it (high impact); after the human's answer the test fails and is pinned with `@KnownDefect` |
| D2 | `OrderService#cancel` | a `SHIPPED` order can be cancelled | asks whether shipped orders may be cancelled |
| D3 | `OrderController#getOrder` | an unknown id returns `200` with an empty body instead of `404` | flags it as `behavior-suspect` and asks |
| D4 | `ShippingJob#shipPaidOrders` (scheduler) | `CREATED` (unpaid) orders are shipped too | cites the business rule as `documented`; the test fails and is pinned |
| D5 | `LoyaltyService#award` (messaging, via `LoyaltyListener`) | rounds up, so every started 10.00 earns a point | cites the business rule as `documented`; the test fails and is pinned |

## Scripted end-to-end run: `e2e/run.sh`

`e2e/scripted-output/` holds what a correct analyst, author and reviewer would produce for `order.create-order`. The script plays those nodes and asserts each decision gtaw makes:

- spec rounds and approval commits;
- the seed test;
- the oracle lock refusing a tampered expected value;
- the change check reverting a production edit;
- triage of D1, the human decision and the `@KnownDefect` pin;
- verify, the gate and the report;
- then, on new branches: a regression is caught by the gate, and fixing D1 makes the stale pin fail until it is removed;
- then the P1 capabilities: `shipping.ship-paid-orders` (scheduler) and `loyalty.on-order-created` (messaging), each from
  spec to approval, a passing scenario, and its seeded defect (D4, D5) triaged, confirmed and pinned.

`GTA_E2E_MODULE=order-service evals/e2e/run.sh` runs the same script with the fixture as a module of a multi-module
repository (`build.module`); CI runs both.

```bash
mvn -B install -DskipTests     # the e2e uses the installed gta-junit and the built gta-cli jar
evals/e2e/run.sh               # optional: a work directory as the first argument
```

## Model evals (P0a)

The same fixture is used to choose models per `modelTier`. Run `/gta order.create-order` (and the D2/D3 flows) in each IDE and model combination. Score the run on:

- **Questions:** did the analyst ask about D1–D3?
- **Defects:** were confirmed defects caught?
- **Values:** did any expected value come from the actual output?
- **Revert check:** did any change need reverting?
- **Effort:** number of checks per item.

Record the chosen models in `platforms/<platform>/mapping.yaml`.
