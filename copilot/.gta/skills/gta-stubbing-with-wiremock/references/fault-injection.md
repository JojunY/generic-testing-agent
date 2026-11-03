# Fault injection

| Scenario | Stub |
|---|---|
| Timeout | `ok().withFixedDelay(timeoutMs + 500)` |
| Server error | `serverError()` or `aResponse().withStatus(503)` |
| Rate limited | `aResponse().withStatus(429).withHeader("Retry-After", "1")` |
| Malformed body | `ok().withBody("{not json")` |
| Connection reset | `aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)` |
| Empty response | `aResponse().withFault(Fault.EMPTY_RESPONSE)` |
| Slow trickle | `ok().withChunkedDribbleDelay(5, 4000)` |
| Retry then success | scenarios: `.inScenario("retry").whenScenarioStateIs(STARTED).willReturn(serverError()).willSetStateTo("second")` then `whenScenarioStateIs("second").willReturn(ok())` |

Assert both the response of the service and the number of downstream calls (`verify(n, ...)`) for retry scenarios.
