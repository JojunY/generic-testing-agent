package com.gta.core.oracle;

import com.gta.core.state.WorkState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssertionScannerTest {
    static final String SCENARIO = "order.create/s01";
    static final String TEST = """
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
        assertTrue(o.tokens().containsAll(List.of("201", "50.00", "CREATED")), o.tokens().toString());
        assertFalse(o.tokens().contains("99.99"), "other scenario leaked in");
    }

    @Test
    void shouldNotCountRequestInputsAsExpectedValues() throws Exception {
        AssertionScanner.ScenarioOracle o = scan(TEST.replace("value(50.00)", "value(51.00)"));

        assertEquals(List.of("50.00"), AssertionScanner.missingThenLiterals(List.of("201", "50.00"), o.tokens()));
    }

    @Test
    void shouldDetectAModifiedLockedValue() throws Exception {
        WorkState.Lock lock = OracleLock.create(scan(TEST), List.of("201", "50.00", "CREATED"), "r1");

        OracleLock.Check check = OracleLock.check(lock, scan(TEST.replace("value(50.00)", "value(51.00)")));

        assertEquals(OracleLock.Verdict.MODIFIED, check.verdict());
        assertEquals(List.of("50.00"), check.missingTokens());
    }

    @Test
    void shouldAllowChangedInputsAndAddedAssertions() throws Exception {
        WorkState.Lock lock = OracleLock.create(scan(TEST), List.of("201", "50.00", "CREATED"), "r1");
        String changed = TEST.replace("\\\"subtotal\\\":50.00", "\\\"subtotal\\\":50.000")
                .replace(".andExpect(jsonPath(\"$.status\").value(CREATED));",
                        ".andExpect(jsonPath(\"$.status\").value(CREATED))\n.andExpect(jsonPath(\"$.id\").exists());");

        AssertionScanner.ScenarioOracle now = scan(changed);

        assertEquals(OracleLock.Verdict.OK, OracleLock.check(lock, now).verdict());
        assertFalse(lock.watched.equals(now.watchedHash()), "input change must change the watched hash");
    }
}
