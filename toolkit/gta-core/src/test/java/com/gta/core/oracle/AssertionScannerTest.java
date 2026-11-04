package com.gta.core.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssertionScannerTest {
    static final String SCENARIO = "order.create/s01";
    static final String TEST =
            """
            package com.acme;
            class OrderIT {
                static final String CREATED = "CREATED";
                @Test
                @Tag("scenario:order.create/s01")
                void shouldCreate() throws Exception {
                    mvc.perform(post("/api/orders").content("{\\"subtotal\\":50.00}"))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.total").value(50.00))
                            .andExpect(jsonPath("$.status").value(CREATED));
                }
                @Test
                @Tag("scenario:order.create/s02")
                void other() {
                    assertThat(total).isEqualTo("99.99");
                }
            }
            """;

    @TempDir
    Path dir;

    private AssertionScanner.ScenarioOracle scan(String source) throws Exception {
        Path file = dir.resolve("OrderIT.java");
        Files.writeString(file, source);
        return AssertionScanner.scan(file, SCENARIO, null);
    }

    @Test
    void shouldCollectExpectedValuesFromAssertionsOfTheScenarioOnly() throws Exception {
        AssertionScanner.ScenarioOracle o = scan(TEST);

        assertEquals(1, o.methods());
        assertTrue(
                o.tokens().containsAll(List.of("201", "50.00", "CREATED")),
                o.tokens().toString());
        assertFalse(o.tokens().contains("99.99"), "other scenario leaked in");
    }

    @Test
    void shouldNotCountRequestInputsAsExpectedValues() throws Exception {
        AssertionScanner.ScenarioOracle o = scan(TEST.replace("value(50.00)", "value(51.00)"));

        assertEquals(List.of("50.00"), AssertionScanner.missingThenLiterals(List.of("201", "50.00"), o.tokens()));
    }

    @Test
    void shouldDetectAModifiedLockedValue() throws Exception {
        Lock lock = OracleLock.create(scan(TEST), List.of("201", "50.00", "CREATED"), "r1");

        OracleLock.Check check = OracleLock.check(lock, scan(TEST.replace("value(50.00)", "value(51.00)")));

        assertEquals(OracleLock.Verdict.MODIFIED, check.verdict());
        assertEquals(List.of("50.00"), check.missingTokens());
    }

    @Test
    void shouldAllowChangedInputsAndAddedAssertions() throws Exception {
        Lock lock = OracleLock.create(scan(TEST), List.of("201", "50.00", "CREATED"), "r1");
        String changed = TEST.replace("\\\"subtotal\\\":50.00", "\\\"subtotal\\\":50.000")
                .replace(
                        ".andExpect(jsonPath(\"$.status\").value(CREATED));",
                        ".andExpect(jsonPath(\"$.status\").value(CREATED))\n.andExpect(jsonPath(\"$.id\").exists());");

        AssertionScanner.ScenarioOracle now = scan(changed);

        assertEquals(OracleLock.Verdict.OK, OracleLock.check(lock, now).verdict());
        assertFalse(lock.watched.equals(now.watchedHash()), "input change must change the watched hash");
    }

    private AssertionScanner.ScenarioOracle scanSource(String body) throws Exception {
        Path f = dir.resolve("ScanIT.java");
        Files.writeString(
                f, "import static org.assertj.core.api.Assertions.assertThat;\n" + "class ScanIT {\n" + body + "\n}\n");
        return AssertionScanner.scan(f, "order.create/s01", dir.resolve("resources"));
    }

    @Test
    void shouldResolveConstantsStatusNamesAndMatchers() throws Exception {
        AssertionScanner.ScenarioOracle o = scanSource(
                """
                    static final String TOTAL = "50.00";
                    @Test @Tag("scenario:order.create/s01")
                    void shouldCreate() throws Exception {
                        assertThat(response.total()).isEqualTo(TOTAL);
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                        mvc.perform(post("/api/orders").content("{\\"subtotal\\":50}")).andExpect(status().isCreated());
                        assertThat(response.body()).isEqualTo(\"\"\"
                            {"id": 1}
                            \"\"\");
                        assertThat(response.count()).isEqualTo(3L);
                    }
                """);

        assertTrue(
                o.tokens().containsAll(java.util.List.of("50.00", "201", "CREATED", "3")),
                o.tokens().toString());
        assertTrue(o.tokens().stream().anyMatch(t -> t.contains("\"id\": 1")), "text blocks are expected values");
        assertEquals(1, o.methods());
    }

    @Test
    void shouldWatchFixturesSqlAndSetupAsInputs() throws Exception {
        Files.createDirectories(dir.resolve("resources"));
        Files.writeString(dir.resolve("resources/s01.request.json"), "{\"subtotal\": 50}");
        String body =
                """
                    @BeforeEach void seed() { repo.save(order()); }
                    @Test @Tag("scenario:order.create/s01") @Sql("classpath:/s01.data.sql")
                    void shouldCreate() { post(fixture("s01.request.json")); assertThat(status).isEqualTo(201); }
                """;
        String before = scanSource(body).watchedHash();

        Files.writeString(dir.resolve("resources/s01.request.json"), "{\"subtotal\": 51}");

        assertFalse(before.equals(scanSource(body).watchedHash()), "a changed fixture changes the watched inputs");
        assertFalse(
                before.equals(scanSource(body.replace("order()", "order(2)")).watchedHash()), "so does the setup");
    }

    @Test
    void shouldFindNothingInAMissingOrBrokenFile() throws Exception {
        assertFalse(AssertionScanner.scan(dir.resolve("None.java"), "order.create/s01", null)
                .found());
        Path broken = dir.resolve("Broken.java");
        Files.writeString(broken, "class Broken { void x( { }");
        assertFalse(AssertionScanner.scan(broken, "order.create/s01", null).found());
    }

    @Test
    void shouldMatchLongThenValuesInsideALargerToken() {
        assertEquals(
                java.util.List.of(),
                AssertionScanner.missingThenLiterals(
                        java.util.List.of("PENDING"), java.util.Set.of("status PENDING_PAYMENT")));
        assertEquals(
                java.util.List.of("5"),
                AssertionScanner.missingThenLiterals(java.util.List.of("5"), java.util.Set.of("50")),
                "short values must match exactly");
    }
}
