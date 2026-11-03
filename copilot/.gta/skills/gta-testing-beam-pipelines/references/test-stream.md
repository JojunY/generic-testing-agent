# TestStream

```java
TestStream<Event> stream = TestStream.create(eventCoder)
    .addElements(TimestampedValue.of(e1, t(0)))
    .advanceWatermarkTo(t(60))
    .addElements(TimestampedValue.of(lateEvent, t(30)))   // late
    .advanceWatermarkToInfinity();
```

Test: on-time vs late panes, allowed lateness, accumulation mode, empty windows, duplicate events (dedup by id).
