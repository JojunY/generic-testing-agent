# Non-GCP and third-party services

- Component tests always use WireMock stubs, never the real service or its sandbox.
- Stub bodies come from the provider's documented contract or recorded sandbox responses (recorded by a human, stored under `src/test/resources/fixtures/`); note their origin in the spec as `documented` with a citation, otherwise they are `observed` or `assumed`.
- Contract drift (the real provider changed) is detected by contract tests (P1 contract skill) or system tests (P4), not by component tests.
- Authentication headers: assert the service sends them; never put real credentials in stubs or fixtures.
