# Suspicious current behaviour

Code describes what happens, not what should happen. Turn these into questions or `behavior-suspect` findings instead of requirements:

- an exception is caught and ignored, or logged and then a success response is returned
- a missing ownership, tenant or role check on data access
- different status codes for the same error in neighbouring endpoints
- a retry without idempotency on a non-idempotent call
- money computed with `double`, or rounding without an explicit mode
- local time (`LocalDateTime.now()`) where the business rule is about a zone or UTC
- `@Transactional` on a private method or a self-call (no proxy, no transaction)
- a scheduled job without a lock in a service that runs several replicas
- configuration defaults that look like test values (timeouts of 0, unlimited retries)

Question format: state what the code does, then ask what it should do, with the current behaviour as one option marked `= 代码现状`.
