# GraphQL (P3)

- Slice: `@GraphQlTest(OrderController.class)` + `GraphQlTester`.
- Component: `HttpGraphQlTester` against the running app.
- Assert `errors` (classification, path) as well as `data`; GraphQL returns HTTP 200 for most errors.
- Authorisation applies per field: include negative tests for protected fields.
