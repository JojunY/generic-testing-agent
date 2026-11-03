---
name: gta-using-embedded-dependencies
description: Runs component tests without any containers on Windows developer machines by choosing, per dependency, the highest-fidelity embedded or emulated replacement - Oracle shared test schema or H2 Oracle mode, BigQuery fakes, the Pub/Sub emulator from Nexus, @EmbeddedKafka, embedded ActiveMQ Artemis for JMS/MQ, in-memory GCS - and reporting the fidelity. Use when a gta test needs a database, broker or cloud service.
---

# Embedded dependencies (no containers)

## Quick start

| Dependency | Use | Fidelity |
|---|---|---|
| Oracle | shared test schema if `environments.oracle.testUrl` is set (`references/oracle.md`), else H2 `MODE=Oracle` | high / low |
| BigQuery | interface + fake (`references/bigquery-fakes.md`); SQL itself in P4 system tests | low |
| Pub/Sub | official emulator downloaded from Nexus by gtaw (`references/pubsub-emulator.md`), else a fake publisher/subscriber | medium / low |
| Kafka | `@EmbeddedKafka` (`references/embedded-kafka.md`) | high |
| MQ (JMS) | embedded ActiveMQ Artemis (`references/jms-artemis.md`) | medium |
| GCS | `LocalStorageHelper` from google-cloud-nio | medium |
| HTTP downstream | WireMock (see the stubbing skill) | high |

Wire everything with `@DynamicPropertySource`. No Docker, Testcontainers, or real cloud: gtaw removes cloud credentials from test runs.

## Workflow

1. Check `./gtaw doctor` output in the brief context: which emulators/test databases are available.
2. Pick the highest-fidelity option that is available; never fail the item because an emulator is missing — fall back and say so.
3. Put shared setup (embedded broker start, schema creation) in the foundation item's support classes so all tests reuse it.
4. State the fidelity you used in your one-line result (`fidelity: low (H2 Oracle mode)`).

## Rules

- Oracle-specific SQL (PL/SQL, `MERGE`, sequences, `ROWNUM`/`FETCH FIRST`, date arithmetic) is not trustworthy on H2; flag scenarios that depend on it.
- Each test uses unique keys or its own schema so tests can run in any order.
- On Windows use temporary directories under the build directory for broker data and keep paths short (`references/windows.md`).

## References

- `references/oracle.md` · `references/bigquery-fakes.md` · `references/pubsub-emulator.md` · `references/embedded-kafka.md`
- `references/jms-artemis.md` · `references/in-memory-fakes.md` · `references/fidelity.md` · `references/windows.md`
