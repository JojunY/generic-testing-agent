---
name: gta-testing-deployed-systems
description: Writes system-layer tests against deployed services on Cloud Run and GKE with Karate - tagged revisions or per-run services, ephemeral namespaces, cross-service business flows with eventual consistency, external sandboxes, and cost and cleanup rules. Use when a gta author brief targets the system layer (P4).
---

# Testing deployed systems (P4)

## Quick start

Karate feature under `src/test/resources/karate/<domain>/<flow-name>.feature`, run only by `gtaw run --layer system`:

```gherkin
@gta @system @flow=order.create @scenario=order.create/s09
Scenario: order is visible to the owner after creation
  Given url baseUrl
  And path '/api/orders'
  And request read('classpath:fixtures/order/create/valid.json')
  When method post
  Then status 201
  And match response.id == '#uuid'
```

## Workflow

1. Environments come from `gtaw env up` (temporary Cloud Run service or GKE namespace) with `allowCloud=true`; never call cloud CLIs yourself.
2. Cross-service flows: drive the entry point, then poll for the final state (Karate `retry until`).
3. External services: vendor sandbox only, a few smoke scenarios.
4. Clean up: `gtaw env down`; `gtaw env gc` removes leftovers by TTL label.

## Rules

- Cloud Run tagged revisions share the main revision's database and do not receive Pub/Sub push, Eventarc or Scheduler traffic; use a separate `gta-<shortRunId>-<purpose>` service for those (`references/cloud-run.md`).
- No production data; resources carry `gta-run-id` and `gta-ttl-hours` labels (`references/cost-control.md`).

## References

- `references/karate.md` · `references/cloud-run.md` · `references/gke.md` · `references/cost-control.md`
