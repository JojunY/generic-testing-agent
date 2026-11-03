---
name: gta-testing-api-contracts
description: Writes contract-level tests for Spring APIs and messages - Pact consumer/provider tests for multi-language consumers, Spring Cloud Contract for pure Spring estates, OpenAPI breaking-change checks with oasdiff, schema conformance with Schemathesis, or Specmatic as a JVM-native option. Use when a gta author brief targets the contract layer.
---

# Testing API contracts

## Quick start

Choose one tool per boundary:

| Situation | Tool |
|---|---|
| consumers in several languages, or owned by other teams | Pact (consumer-driven, broker or files) |
| all consumers are Spring services in the same estate | Spring Cloud Contract |
| the OpenAPI document is the contract | oasdiff (breaking changes) + Schemathesis or Specmatic (conformance) |

## Workflow

1. Identify the consumer and provider of the boundary from the spec's Boundary section.
2. Provider side in this repository: verify the provider against the published contracts in a component-style test (no containers; embedded dependencies).
3. Consumer side: record expectations of the downstream as a contract instead of an ad-hoc WireMock stub.
4. OpenAPI: compare the current document with the base branch's version; a breaking change without a spec change is a finding.

## Rules

- Contracts come from consumers or documented APIs, never from the provider's current behaviour alone.
- Contract tests are tagged `contract` and use the same scenario tags as other gta tests.
- All tools and dependencies come through Nexus; the Pact broker URL is configuration, not code.

## References

- `references/pact.md` · `references/spring-cloud-contract.md` · `references/oasdiff.md` · `references/schemathesis.md` · `references/specmatic.md`
