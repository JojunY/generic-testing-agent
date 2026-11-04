package com.gta.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.run.RunResult;
import com.gta.core.run.TestOutcome;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** When a run counts as having reached the system under test, and how runs are summarised (plan §5.3, §5.4). */
class TestRunsTest {
    private static TestOutcome outcome(String o, boolean sutReached) {
        TestOutcome c = new TestOutcome();
        c.className = "com.acme.OrderIT";
        c.method = "should";
        c.outcome = o;
        c.sutReached = sutReached;
        return c;
    }

    @Test
    void shouldLockOnlyAfterTheSystemUnderTestWasReached() {
        RunResult r = new RunResult();
        assertFalse(TestRuns.reached(r), "no test ran");

        r.cases.add(outcome("error", false));
        assertFalse(TestRuns.reached(r), "failed in setup");

        r.cases.add(outcome("failed", true));
        assertTrue(TestRuns.reached(r));

        r.timedOut = true;
        assertFalse(TestRuns.reached(r));
    }

    @Test
    void shouldSummariseCountsFailuresAndPinnedDefects() {
        RunResult r = new RunResult();
        r.cases.addAll(List.of(outcome("passed", true), outcome("known-defect", true), outcome("failed", true)));
        r.compileError = true;
        r.compileMessage = "src/A.java:1 x";
        r.timedOut = true;

        Map<String, Object> s = TestRuns.summarize(r);

        assertEquals(3, s.get("tests"));
        assertEquals(1L, s.get("passed"));
        assertEquals(1L, s.get("knownDefect"));
        assertEquals(1, ((List<?>) s.get("failures")).size());
        assertEquals("src/A.java:1 x", s.get("compile"));
        assertEquals(true, s.get("timedOut"));
    }
}
