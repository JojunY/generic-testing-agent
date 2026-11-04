package com.gta.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.gta.core.run.FailureClassifier;
import org.junit.jupiter.api.Test;

/**
 * {@code @KnownDefect(failure = ...)} compares this fingerprint at test time with the one gtaw recorded (plan §5.7), so
 * gta-core's copy of the algorithm must give the same result on every input.
 */
class FingerprintTest {
    private static final String[][] SAMPLES = {
        {"org.opentest4j.AssertionFailedError", "expected: <20.00> but was: <25.00>"},
        {
            "java.lang.IllegalStateException",
            "order 3f2c9a1e-7b4d-4e8f-9a2b-1c3d5e7f9a0b not found at 2026-11-04T09:15:30.123Z"
        },
        {"java.lang.NullPointerException", null},
        {null, "plain   message\nwith\twhitespace"},
        {"com.acme.PricingException", "id 12345678 and 0xDEADBEEF at @1a2b3c4d"},
    };

    @Test
    void shouldMatchTheFingerprintGtawRecords() {
        assertEquals(Fingerprint.VOLATILE, FailureClassifier.VOLATILE, "the volatile-part pattern is shared");
        for (String[] s : SAMPLES) {
            assertEquals(
                    FailureClassifier.knownDefectFingerprint(s[0], s[1]),
                    Fingerprint.of(s[0], s[1]),
                    s[0] + " | " + s[1]);
        }
    }

    @Test
    void shouldIgnoreVolatilePartsButNotTheCause() {
        assertEquals(
                Fingerprint.of("X", "order 3f2c9a1e-7b4d-4e8f-9a2b-1c3d5e7f9a0b failed"),
                Fingerprint.of("X", "order 00000000-1111-2222-3333-444444444444 failed"));
        assertNotEquals(Fingerprint.of("X", "total too high"), Fingerprint.of("X", "total too low"));
        assertNotEquals(Fingerprint.of("X", "same"), Fingerprint.of("Y", "same"));
    }
}
