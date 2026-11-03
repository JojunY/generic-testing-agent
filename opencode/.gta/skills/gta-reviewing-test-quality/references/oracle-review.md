# Oracle review checklist

Reject when any answer is "no":

1. Every THEN clause has an assertion with the exact expected value from the spec.
2. Assertions are on the observable result (response, persisted state read independently, published message), not on mocks of the code under test.
3. The arrange step really produces the WHEN (e.g. the stub delay exceeds the configured timeout; the user really is not the owner).
4. The side effects that must not happen are asserted absent.
5. Nothing hides failures: no `@Disabled`, no assumptions, no broad `catch`, no `Thread.sleep`.
6. The test is isolated: unique data, no dependence on test order, no shared mutable static state.
7. If gtaw flagged restructured assertions or inputs changed after a failure, the meaning is unchanged and the inputs were not bent to make the test pass.

Accept with a reason that names what is checked ("asserts 503 and PENDING_PAYMENT after a 3.5 s stubbed delay").
