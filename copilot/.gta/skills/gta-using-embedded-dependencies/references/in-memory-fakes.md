# In-memory fakes

Use when no embedded or emulated dependency exists.

- Fake the narrowest interface the service owns (a repository, a publisher, a client wrapper), not the vendor SDK.
- The fake keeps state in memory and lets the test read what happened (`fakePublisher.published("order-created")`).
- Register it with `@TestConfiguration` + `@Primary` or `@MockitoBean` replaced by a real fake object.
- Keep fakes in the test support package created by the foundation item so all tests share one implementation.
- A fake cannot catch integration bugs: report `fidelity: low`.

GCS: `com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper.getOptions().getService()` gives an in-memory `Storage`.
