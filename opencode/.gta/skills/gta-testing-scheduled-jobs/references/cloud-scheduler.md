# Cloud Scheduler → HTTP endpoint

- Treat the trigger endpoint as an API: test it with the API skill (component layer).
- Delivery is at-least-once: the same `X-CloudScheduler-JobName` + `X-CloudScheduler-ScheduleTime` may arrive twice; assert one effect.
- Authentication: requests carry an OIDC token; assert unauthenticated requests are rejected (`401`/`403`).
- Running the real job in GCP (`gcloud scheduler jobs run`) is a P4 system test.
