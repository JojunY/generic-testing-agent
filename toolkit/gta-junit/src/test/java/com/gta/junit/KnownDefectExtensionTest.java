package com.gta.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

class KnownDefectExtensionTest {
    static final String FINGERPRINT = "d010080d89aa";

    static class StillBroken {
        @Test
        @KnownDefect(id = "order.create/s03", failure = FINGERPRINT)
        void pinned() {
            fail("discount expected 10.00 but was 0.00");
        }
    }

    static class BrokenDifferently {
        @Test
        @KnownDefect(id = "order.create/s03", failure = FINGERPRINT)
        void pinned() {
            fail("discount expected 10.00 but was 5.00");
        }
    }

    static class Fixed {
        @Test
        @KnownDefect(id = "order.create/s03", failure = FINGERPRINT)
        void pinned() {}
    }

    private static Events run(Class<?> c) {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(c))
                .execute()
                .testEvents();
    }

    @Test
    void shouldAbortWhenTheKnownDefectIsStillThere() {
        Events events = run(StillBroken.class);

        assertEquals(1, events.aborted().count());
        String message = events.aborted().stream()
                .findFirst()
                .orElseThrow()
                .getPayload(org.junit.platform.engine.TestExecutionResult.class)
                .orElseThrow()
                .getThrowable()
                .orElseThrow()
                .getMessage();
        assertTrue(message.startsWith(KnownDefectExtension.ABORT_PREFIX), message);
    }

    @Test
    void shouldFailWhenTheTestFailsForAnotherReason() {
        assertEquals(1, run(BrokenDifferently.class).failed().count());
    }

    @Test
    void shouldFailWhenTheDefectIsFixed() {
        assertEquals(1, run(Fixed.class).failed().count());
    }
}
