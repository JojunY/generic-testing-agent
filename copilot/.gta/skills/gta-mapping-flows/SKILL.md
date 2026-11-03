---
name: gta-mapping-flows
description: Traces one business flow of a Java/Spring service from its entry point (controller method, scheduled job, message listener, batch job) to all of its side effects, and scores its risk. Use when writing the Boundary, refs and risk of a gta behaviour spec.
---

# Mapping a flow

## Quick start

1. Start at the entry point named in the brief (`method:<fqcn>#<method>`).
2. Follow every call into production code until you reach a side effect: database write, message sent, HTTP call, file written, cache updated, metric emitted, response returned.
3. Record each place the behaviour depends on as a `ref`: the methods (`method:`), Spring bean factory methods that configure it (`bean:`), and configuration keys (`config:`).
4. Write the Boundary: in scope, out of scope (neighbouring flows), external systems.
5. Score risk = probability (1-3) × impact (1-3).

## Workflow

- Follow injected beans, not only static calls: interfaces resolve to the `@Component`/`@Bean` that implements them; framework callbacks (`SkipPolicy`, `ErrorHandler`, `@TransactionalEventListener`) are part of the flow.
- Note transaction boundaries (`@Transactional`), retries (`@Retryable`, resilience4j), timeouts and their config keys; they become scenarios.
- Stop at the boundary of another flow (e.g. a message consumed by another service or another listener) and list it as out of scope.
- Keep the list of refs short and precise: the methods whose change would change this flow's observable behaviour.

## Rules

- Refs must resolve: `method:com.acme.order.OrderService#authorize`, `bean:com.acme.batch.ImportConfig#importStep`, `config:payment.gateway.timeout`. gtaw computes their hashes.
- Do not guess side effects you cannot see in code; ask a question instead.

## References

- `references/entrypoints.md` — how gtaw names flows and which annotations are entry points
- `references/risk-scoring.md` — probability × impact table and examples
