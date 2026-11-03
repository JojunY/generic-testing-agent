# Spring Cloud Contract

- Contracts (Groovy/YAML) live in the provider under `src/test/resources/contracts/`.
- The verifier plugin generates provider tests from them; a base class sets up the app (`RestAssuredMockMvc.standaloneSetup` or `webAppContextSetup`).
- Stubs are published as a jar to Nexus; consumers use `@AutoConfigureStubRunner(ids = "com.acme:order-service:+:stubs", stubsMode = REMOTE)` pointing at Nexus.
- Use it when every consumer is a JVM/Spring service; otherwise use Pact.
