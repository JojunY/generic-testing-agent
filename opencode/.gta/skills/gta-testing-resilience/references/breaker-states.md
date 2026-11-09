# Breaker states in tests

- **Closed to open**: the breaker evaluates once `minimum-number-of-calls` calls are in the sliding window; it opens
  when the failure rate reaches `failure-rate-threshold` (percent). With a window of 10, a minimum of 5 and a threshold
  of 50, five failing calls open it.
- **Open**: calls go straight to the fallback (`CallNotPermittedException` without one); the stub sees no request.
- **Half-open**: after `wait-duration-in-open-state` the breaker lets `permitted-number-of-calls-in-half-open-state`
  calls through. Set the wait to a short test value (`@SpringBootTest(properties = "resilience4j.circuitbreaker.instances.inventory.wait-duration-in-open-state=200ms")`),
  then wait for the state with Awaitility (`await().atMost(Duration.ofSeconds(2)).until(() -> breaker.getState() == HALF_OPEN)`)
  only when `automatic-transition-from-open-to-half-open-enabled` is true; otherwise the next call moves it.
- **Back to closed**: successful half-open calls (stub now returns 200) close it; a failure opens it again.
- Drive state directly only to set up a scenario (`breaker.transitionToOpenState()`), never to assert behaviour the
  configuration should produce.
