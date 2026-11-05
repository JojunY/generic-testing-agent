# Temporal

Run the workflow in `TestWorkflowEnvironment` (from `io.temporal:temporal-testing`, test scope, same version as
`temporal-sdk`): an in-process test server with time skipping, no Temporal cluster.

```java
TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
Worker worker = env.newWorker("restock");
worker.registerWorkflowImplementationTypes(RestockWorkflowImpl.class);
worker.registerActivitiesImplementations(activities);   // a fake that records calls
env.start();
RestockWorkflow workflow = env.getWorkflowClient()
        .newWorkflowStub(RestockWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue("restock").build());
```

- Fake the activities with a small class implementing the `@ActivityInterface` that records its calls and can be told to
  fail. Assert on what it recorded (`assertThat(activities.cancelled).containsExactly("po-1")`). A Mockito
  `verify(...)` failure message carries source line numbers, which makes a pinned `@KnownDefect` fingerprint fragile.
- If you do use Mockito, create the mock with `withSettings().withoutAnnotations()` so Temporal does not read Mockito's
  generated annotations.
- A failing activity is retried until its retry options stop it: make the failure the scenario needs and rely on the
  workflow's own `RetryOptions`, never change them in the test.
- Timers and `Workflow.sleep` run instantly with time skipping; use `env.sleep(Duration)` to move time between signals.
- Close the environment after each test (`env.close()`).
