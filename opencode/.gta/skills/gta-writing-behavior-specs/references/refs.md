# Refs

Refs say which code a requirement depends on. gtaw hashes them; when a hash changes after approval, the spec becomes `stale` and is re-confirmed in PR mode.

| Form | Hash of | Example |
|---|---|---|
| `method:<fqcn>#<method>` | the method body (all overloads) | `method:com.acme.order.OrderService#authorize` |
| `bean:<fqcn>#<method>` | the `@Bean` factory method (e.g. a Batch `Step` with `.skipLimit()`) | `bean:com.acme.batch.ImportConfig#importStep` |
| `config:<key>` | the value in `application*.yml/properties` | `config:payment.gateway.timeout` |
| `file:<path in the module>` | the file, for non-Java behaviour such as a migration script | `file:src/main/resources/db/migration/V2__customer_tier.sql` |

Rules:

- Use the fully qualified class name and the method name, no parameter list.
- Reference framework callbacks that change behaviour (a `SkipPolicy` bean, an `ErrorHandler`).
- Several `ref=` entries in one comment are fine. Do not write `refHash`; gtaw fills it in.
