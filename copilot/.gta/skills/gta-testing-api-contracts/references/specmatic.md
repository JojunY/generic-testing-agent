# Specmatic

- JVM-native: turns an OpenAPI document into contract tests for the provider and stubs for consumers.
- Provider tests run with JUnit against the started app; stubs replace a downstream described by its OpenAPI.
- Prefer it over Schemathesis when Python is not available and the OpenAPI document is the agreed contract.
