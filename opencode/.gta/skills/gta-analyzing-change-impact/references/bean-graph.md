# Following the bean graph

Static call graphs miss Spring wiring. Also follow:

- interface → implementing `@Component`/`@Service`, or the `@Bean` method returning it
- `@Bean` factory methods that configure behaviour (Batch steps, retry templates, `RestClient` builders with timeouts)
- framework callbacks registered as beans (`SkipPolicy`, `ItemProcessListener`, `ErrorHandler`, `HandlerInterceptor`)
- configuration properties classes (`@ConfigurationProperties`) and the keys they bind

Reference what you find as `bean:<fqcn>#<factoryMethod>` and `config:<key>` so future changes mark the spec stale.
