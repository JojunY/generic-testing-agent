package com.gta.core.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The monotonic oracle lock (plan §5.4): locked values may not change or disappear; assertions may be added. */
class OracleLockTest {
    private static AssertionScanner.ScenarioOracle oracle(List<String> statements, Set<String> tokens) {
        return new AssertionScanner.ScenarioOracle(1, statements, tokens, "watched-1");
    }

    private static final AssertionScanner.ScenarioOracle FIRST =
            oracle(List.of("isEqualTo(201)", "isEqualTo(\"50.00\")", "isNotNull()"), Set.of("201", "50.00"));

    @Test
    void shouldLockOnlyTheSpecsValuesTheTestAsserts() {
        Lock lock = OracleLock.create(FIRST, List.of("201", "50.00", "CREATED"), "r1");

        assertEquals(List.of("201", "50.00"), lock.tokens, "CREATED is not asserted yet, so it is not locked");
        assertEquals(List.of("isEqualTo(201)", "isEqualTo(\"50.00\")"), lock.statements);
        assertEquals("watched-1", lock.watched);
        assertEquals("r1", lock.reachedInRun);
    }

    @Test
    void shouldDetectAChangedValueARestructuredAssertionAndAcceptAnAddedOne() {
        Lock lock = OracleLock.create(FIRST, List.of("201", "50.00"), "r1");

        assertEquals(OracleLock.Verdict.OK, OracleLock.check(lock, FIRST).verdict());
        assertEquals(OracleLock.Verdict.OK, OracleLock.check(null, FIRST).verdict(), "nothing locked yet");
        OracleLock.Check changed = OracleLock.check(
                lock, oracle(List.of("isEqualTo(201)", "isEqualTo(\"51.00\")"), Set.of("201", "51.00")));
        assertEquals(OracleLock.Verdict.MODIFIED, changed.verdict());
        assertEquals(List.of("50.00"), changed.missingTokens());
        assertEquals(
                OracleLock.Verdict.STRUCTURAL_CHANGE,
                OracleLock.check(
                                lock, oracle(List.of("hasStatus(201)", "isEqualTo(\"50.00\")"), Set.of("201", "50.00")))
                        .verdict());
        assertEquals(
                OracleLock.Verdict.OK,
                OracleLock.check(
                                lock,
                                oracle(
                                        List.of("isEqualTo(201)", "isEqualTo(\"50.00\")", "hasSize(1)"),
                                        Set.of("201", "50.00", "1")))
                        .verdict(),
                "added assertions are fine");
    }

    @Test
    void shouldFingerprintStatementsIndependentlyOfOrder() {
        assertEquals(OracleLock.fingerprint(List.of("a", "b")), OracleLock.fingerprint(List.of("b", "a")));
    }
}
