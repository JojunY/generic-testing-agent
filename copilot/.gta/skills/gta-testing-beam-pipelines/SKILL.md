---
name: gta-testing-beam-pipelines
description: Tests Apache Beam / Dataflow pipelines locally on the DirectRunner - business logic outside DoFns, transforms with TestPipeline and PAssert, streaming semantics with TestStream (watermarks, late data, windows, triggers), coders, retry idempotency and data-quality checks. Use when a gta author brief targets a beam-pipeline scenario (P3).
---

# Testing Beam pipelines

## Quick start

```java
@RegisterExtension
final TestPipelineExtension pipeline = TestPipelineExtension.create();   // JUnit 5+: beam-sdks-java-testing-junit

@Test
@Tag("scenario:usage.aggregate/s02")
void shouldSumUsagePerAccountWhenEventsArriveOutOfOrder(TestPipeline p) {
    PCollection<KV<String, Long>> out = p.apply(Create.of(events())).apply(new AggregateUsage());
    PAssert.that(out).containsInAnyOrder(KV.of("acc-1", 30L), KV.of("acc-2", 5L));
    p.run().waitUntilFinish();
}
```

## Workflow

1. Logic that lives outside DoFns: plain unit tests.
2. Transforms: `TestPipeline` + `PAssert`; keep abandoned-node enforcement on.
3. Streaming: `TestStream` to advance watermarks and inject late data (`references/test-stream.md`).
4. Retries: bundles can be retried, so side effects inside DoFns must be idempotent — test calling them twice.
5. IO: Pub/Sub emulator and in-memory GCS where possible; BigQuery IO and real Dataflow runs are P4 (`references/dataflow-runs.md`).

## Rules

- `PAssert` must be applied before `run()`.
- Compare aggregates with an independently computed expectation (differential oracle), not with the pipeline's own helper code.

## References

- `references/test-stream.md` · `references/dataflow-runs.md`
