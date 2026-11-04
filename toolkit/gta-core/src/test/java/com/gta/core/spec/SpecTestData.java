package com.gta.core.spec;

/** A small, valid behaviour spec used by several tests. */
public final class SpecTestData {
    public static final String CREATE_ORDER =
            """
            # order.create — Create an order
            <!-- gta: flowId=order.create capability=rest-api risk=6 status=draft -->

            ## Boundary
            - In scope: POST /api/orders
            - Out of scope: order.cancel
            - External: none

            ```mermaid
            sequenceDiagram
              participant C as Client
              participant S as Service
              C->>S: POST /api/orders
              S-->>C: 201
            ```

            ## Access
            | Role | Operation | Ownership constraint | Source |
            |---|---|---|---|
            | anonymous | create order | none | observed |

            ## Requirements

            ### Requirement: Create an order
            <!-- gta: id=order.create/r01 ref=method:com.acme.order.OrderController#create -->
            WHEN a client posts a valid order, THE order service SHALL respond 201.

            #### Scenario: valid order
            <!-- gta: id=order.create/s01 source=observed hint.layer=component hint.oracle=response -->
            - **WHEN** customer `c-1` posts subtotal `50.00`
            - **THEN** the response status is `201`
            - **AND** the total is `50.00`

            #### Scenario: order at the threshold
            <!-- gta: id=order.create/s02 source=assumed hint.layer=component -->
            - **WHEN** customer `c-1` posts subtotal `100.00`
            - **THEN** the discount is `0.00`

            ## Invariants

            ## Open questions
            <!-- gta: id=order.create/q01 impact=high affects=order.create/s02 -->
            1. [high impact] Should an order of exactly 100.00 get the discount?
               - [ ] A) No (= current code)
               - [ ] B) Yes → s02 THEN discount `10.00`

            ## Findings
            """;

    private SpecTestData() {}
}
