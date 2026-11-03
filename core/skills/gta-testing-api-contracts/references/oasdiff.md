# oasdiff (breaking OpenAPI changes)

- Compare `openapi.yaml` (or the springdoc-generated document) of the base branch with the current one: `oasdiff breaking base.yaml current.yaml --fail-on ERR`.
- Run in CI next to `gtaw gate`; a breaking change needs a matching spec change and a consumer decision.
- springdoc-generated documents describe current code (`observed`); a hand-maintained document reviewed by consumers can be `documented`.
