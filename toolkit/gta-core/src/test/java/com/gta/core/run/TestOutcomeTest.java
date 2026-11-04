package com.gta.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Results map to scenarios through the scenario: tag gta-junit records (plan §5.3). */
class TestOutcomeTest {
    @Test
    void shouldTakeTheScenarioFromItsTag() {
        TestOutcome c = new TestOutcome();
        c.tags = new ArrayList<>(List.of("gta", "component", "scenario:order.create/s01"));
        c.outcome = "passed";

        assertEquals("order.create/s01", c.scenarioId());
        assertTrue(c.passed());

        c.tags = new ArrayList<>(List.of("gta"));
        c.outcome = "known-defect";
        assertEquals(null, c.scenarioId());
        assertTrue(c.passed(), "a pinned defect keeps the build green");
        c.outcome = "failed";
        assertFalse(c.passed());
    }
}
