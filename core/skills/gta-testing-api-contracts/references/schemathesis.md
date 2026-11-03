# Schemathesis

- Property-based conformance testing from the OpenAPI document: random valid/invalid requests, checks status codes, response schemas and server errors.
- Stateful mode chains operations through OpenAPI links (create → read → delete).
- It is a Python CLI; run it in CI against the app started for system or component testing, not on developer machines that lack Python.
- Findings (5xx, schema violations) become spec findings or new scenarios; they are not auto-fixed.
