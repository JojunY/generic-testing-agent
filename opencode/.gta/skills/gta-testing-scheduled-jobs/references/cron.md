# Cron expressions

```java
@ParameterizedTest
@CsvSource({
    "2026-03-28T23:00:00Z, 2026-03-29T00:30:00Z",   // Europe/Berlin DST start
    "2026-01-31T03:00:00Z, 2026-02-01T00:30:00Z"    // month end
})
void shouldFireAtNextExpectedInstant(Instant now, Instant expected) {
    CronExpression cron = CronExpression.parse("0 30 1 * * *");
    ZonedDateTime next = cron.next(now.atZone(ZoneId.of("Europe/Berlin")));
    assertThat(next.toInstant()).isEqualTo(expected);
}
```

Spring cron has six fields (seconds first). Test the zone the `@Scheduled(zone = ...)` declares, not the machine's default.
