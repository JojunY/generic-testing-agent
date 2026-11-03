# MQ via JMS: embedded ActiveMQ Artemis

When the service talks to MQ through the JMS API (e.g. the IBM MQ JMS client or Spring JMS), test the JMS semantics against an embedded Artemis broker:

```properties
# test properties
spring.artemis.mode=embedded
spring.artemis.embedded.queues=ORDERS.IN,ORDERS.BACKOUT
```

- Dependencies (test scope): `spring-boot-starter-artemis` and `artemis-jakarta-server` (versions from the Boot parent).
- Provide the test `ConnectionFactory` from Artemis; keep the production IBM MQ factory out of the test context with a profile or `@TestConfiguration`.
- What this proves: listener logic, transactions (rollback → redelivery), redelivery limit → backout/DLQ, selectors, message conversion.
- What it does not prove: IBM MQ channel/queue manager configuration, MQ-specific headers (`JMS_IBM_*`), backout queue names configured on the queue manager. Those are system-level (P4); report `fidelity: medium`.
- If the code uses the native IBM MQ classes (not JMS), use a fake around the client wrapper and report `fidelity: low`.
