#### Scenario: an invoice of exactly 100.00 gets the discount (incident)
<!-- gta: id=invoice.quote/s03 source=documented doc=.gta/specs/invoice/quote.incidents.md quote="invoices of 100 or more get 10 % off" hint.layer=component hint.oracle=response hint.intent=reproducing priority=P0 -->
- **WHEN** a client quotes an amount of 100.00
- **THEN** the response status is `200` with total `90.00`

