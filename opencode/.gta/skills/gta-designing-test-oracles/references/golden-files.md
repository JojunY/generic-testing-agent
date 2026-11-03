# Golden files

Use for large structured outputs (reports, batch output files, rendered documents).

- Location: `src/test/resources/golden/<domain>/<flow-name>/<case>.approved.json`
- Mask volatile fields (ids, timestamps) before comparing; list the masked JSON paths in the test.
- Compare with JSON-aware equality (e.g. JSONAssert strict mode, or AssertJ recursive comparison on parsed objects), never plain string equality of pretty-printed JSON.
- A golden file is part of the oracle: changing it after the test first ran is a change of expected values and is routed to the reviewer.

```java
String actual = mask(objectMapper.writeValueAsString(report), "$.generatedAt", "$.id");
String expected = Files.readString(Path.of("src/test/resources/golden/invoice/monthly/basic.approved.json"));
JSONAssert.assertEquals(expected, actual, JSONCompareMode.STRICT);
```

Only create a golden file from the spec or a human-approved output, not from the first run.
