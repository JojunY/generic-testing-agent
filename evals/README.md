# Evals

## Fixture: `fixtures/order-service`

A small Spring Boot 4.1 service: a REST API to create, get, cancel and ship orders with a volume discount, a scheduled shipping job, a loyalty listener on an application event, an admin API behind Spring Security, cached prices, an archive Spring Batch job and Flyway customer migrations. `docs/business-rules.md` states two rules the code breaks. It has nine seeded defects, each marked `SEEDED DEFECT` in the code:

| Id | Where | Defect | A correct gta run… |
|---|---|---|---|
| D1 | `PricingService#discount` | `>` instead of `>=`: an order of exactly the threshold gets no discount, although the Javadoc says "at least" | asks about it (high impact); after the human's answer the test fails and is pinned with `@KnownDefect` |
| D2 | `OrderService#cancel` | a `SHIPPED` order can be cancelled | asks whether shipped orders may be cancelled |
| D3 | `OrderController#getOrder` | an unknown id returns `200` with an empty body instead of `404` | flags it as `behavior-suspect` and asks |
| D4 | `ShippingJob#shipPaidOrders` (scheduler) | `CREATED` (unpaid) orders are shipped too | cites the business rule as `documented`; the test fails and is pinned |
| D5 | `LoyaltyService#award` (messaging, via `LoyaltyListener`) | rounds up, so every started 10.00 earns a point | cites the business rule as `documented`; the test fails and is pinned |
| D6 | `SecurityConfig#api` (security) | the admin API only needs a login, not role ADMIN | same, and the brief loads the security skill |
| D7 | `PriceService#update` (caching) | no cache eviction, so the next read is stale | same, and the brief loads the caching skill |
| D8 | `ArchiveJobConfig#archiveShipped` (spring-batch) | cancelled orders are archived too | same |
| D9 | `V2__customer_tier.sql` (db-migration) | `>` instead of `>=`: 100 points stay BASIC | same, with `ref=file:` |
| D17 | `QuoteController#requestQuote` (websocket, STOMP) | `>` instead of `>=`: an order of exactly 10 items gets no bulk discount | same, and the brief loads the streaming-endpoints skill |

## Fixture: `fixtures/catalog-service`

A reactive Spring Boot 4.1 catalog: a WebFlux endpoint, a GraphQL query, a gRPC service (stubs generated from
`src/main/proto`), a Temporal workflow and a Beam transform, each with a seeded defect that
`docs/business-rules.md` contradicts.

| Id | Where | Defect |
|---|---|---|
| D10 | `ProductController#reserve` (webflux) | no stock check: a reservation larger than the stock succeeds |
| D11 | `ProductGraphqlController#productsUpTo` (graphql-api) | `<` instead of `<=`: a product at exactly the maximum is left out |
| D12 | `StockGrpcService#getStock` (grpc-api) | `>=` instead of `>`: a product with no stock is reported available |
| D14 | `RestockWorkflowImpl#restock` (workflow, Temporal) | no compensation: a failed stock update leaves the supplier order open |
| D15 | `LowStockTransform#expand` (beam-pipeline) | off by one: a product with exactly 2 in stock is left off the low-stock report |

`e2e/run-catalog.sh` takes the five flows from spec to a pinned defect with no `phase` setting: their capabilities are on since P3.

## Fixture: `fixtures/invoice-service`

A Spring Boot 4.1 MVC service built with Gradle (Kotlin DSL, no pom.xml), with one seeded defect that
`docs/business-rules.md` contradicts.

| Id | Where | Defect |
|---|---|---|
| D13 | `InvoiceController#quote` (rest-api) | `>` instead of `>=`: an invoice of exactly 100 gets no discount |
| D16 | `InvoiceController#lateFee`, added on the branch by the PR step | `>=` instead of `>`: an invoice paid on its due day is charged a late fee |

`e2e/run-gradle.sh` checks that doctor and the profile read `build.gradle.kts`, adds gta-junit to the build script in the
foundation item, and takes the flow from spec to a pinned defect with every test run through Gradle. It then reproduces a
reported incident, and runs PR mode on a branch that changes the discount and adds a late fee: the catching scenario of
the new endpoint catches D16, a human confirms it, and the report counts one catching hit. It needs Gradle
8.14 or newer on PATH and gta-junit in the local Maven repository (`mvn install`).

## Scripted end-to-end run: `e2e/run.sh`

`e2e/scripted-output/` holds what a correct analyst, author and reviewer would produce for `order.create-order`. The script plays those nodes and asserts each decision gtaw makes:

- spec rounds and approval commits;
- the seed test;
- the oracle lock refusing a tampered expected value;
- the change check reverting a production edit;
- triage of D1, the human decision and the `@KnownDefect` pin;
- verify, the gate and the report;
- then, on new branches: a regression is caught by the gate, and fixing D1 makes the stale pin fail until it is removed;
- then the P1 and P2 capabilities: `shipping.ship-paid-orders` (scheduler), `loyalty.on-order-created` (messaging),
  `admin.purge-cancelled` (security), `price.update-price` (caching), `archive-job.archive-orders` (spring-batch) and
  `db.customer-tier` (db-migration), each from spec to approval, a passing scenario, and its seeded defect (D4-D9)
  triaged, confirmed and pinned;
- then `quote.request-quote` (websocket, on by default since P4): a STOMP client against the random port
  passes s01 and catches D17.

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
