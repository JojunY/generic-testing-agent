package com.gta.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Only gtaw decides what passed (plan §5.3): a run without tests or with a compile error never passes. */
class RunResultTest {
    private static TestOutcome outcome(String o) {
        TestOutcome c = new TestOutcome();
        c.outcome = o;
        return c;
    }

    @Test
    void shouldPassOnlyWhenEveryTestRanAndPassed() {
        RunResult r = new RunResult();
        assertFalse(r.allPassed(), "no test ran");

        r.cases.add(outcome("passed"));
        assertTrue(r.allPassed());

        r.compileError = true;
        assertFalse(r.allPassed(), "a compile error never passes");
    }

    @Test
    void shouldListFailuresButNotSkippedTests() {
        RunResult r = new RunResult();
        r.cases.addAll(List.of(outcome("passed"), outcome("skipped"), outcome("failed"), outcome("error")));

        assertEquals(
                List.of("failed", "error"),
                r.failures().stream().map(c -> c.outcome).toList());
        assertFalse(r.allPassed());
    }
}
