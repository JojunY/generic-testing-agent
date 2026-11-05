# Test identities

| Service uses | In the test | Notes |
|---|---|---|
| OAuth2 resource server (JWT) | `.with(jwt().jwt(j -> j.subject("c-1").claim("tenant", "t-1")).authorities(new SimpleGrantedAuthority("SCOPE_orders.read")))` | scopes become `SCOPE_` authorities by default; a custom `JwtAuthenticationConverter` maps claims, so set the claim the converter reads |
| Opaque tokens | `.with(opaqueToken().attributes(a -> a.put("sub", "c-1")))` | |
| Form login / sessions | `.with(user("c-1").roles("CUSTOMER"))`, plus `.with(csrf())` on POST/PUT/DELETE | |
| OAuth2 login (browser) | `.with(oauth2Login().attributes(a -> a.put("sub", "c-1")))` | |
| Method security only | `@WithMockUser(username = "c-1", roles = "ADMIN")` on the test method | the bean must come from the context |
| Service-to-service client credentials | stub the token endpoint with WireMock (stubbing skill) | never call the real authorization server |

The JWT decoder must not reach the issuer at context start: set `spring.security.oauth2.resourceserver.jwt.issuer-uri`
to a WireMock URL, or provide a `JwtDecoder` bean in a `<Name>TestConfig` when the request post-processors are used
(they bypass decoding).
