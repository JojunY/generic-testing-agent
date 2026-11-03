# Pub/Sub emulator

> Phase: the emulator download through `distribution.toolsUrl` arrives with the messaging capability (P3). Until then, P0 items that touch Pub/Sub use the fake bean described at the end (`fidelity: low`).

The official emulator is a Java program. The platform team uploads it to the Nexus raw repository; `distribution.toolsUrl` points there and the foundation support class starts it on a free port with the local JDK.

```java
@DynamicPropertySource
static void pubsub(DynamicPropertyRegistry r) {
    r.add("spring.cloud.gcp.pubsub.emulator-host", PubSubEmulator::hostPort);   // support class from the foundation item
    r.add("spring.cloud.gcp.project-id", () -> "gta-test");
}
```

- Create topics and subscriptions in the support class before the context starts.
- Supported: publish/pull, dead-letter topics, filtering, ordering keys.
- Not supported: IAM, schemas (protobuf/avro validation), single message transforms, BigQuery/Cloud Storage subscriptions — test those in P4.
- If the emulator is not available, replace `PubSubTemplate` with a fake bean and report `fidelity: low`.
