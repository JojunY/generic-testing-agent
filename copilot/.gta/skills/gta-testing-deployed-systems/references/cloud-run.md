# Cloud Run

- `--no-traffic --tag gta-<shortRunId>` gives a revision URL without affecting users, but shares the service's database and does not receive push subscriptions, Eventarc or Scheduler calls.
- For event-triggered flows deploy a temporary service `gta-<shortRunId>-<purpose>` with its own test configuration and temporary subscriptions.
- Cloud Run Jobs: tasks may retry and are sharded by `CLOUD_RUN_TASK_INDEX`; assert idempotency across retries.
- Authenticate with an identity token for the test service account; never use personal credentials.
