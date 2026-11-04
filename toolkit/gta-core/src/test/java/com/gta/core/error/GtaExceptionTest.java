package com.gta.core.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The unified error object every gtaw command prints (plan §5.6). */
class GtaExceptionTest {
    @Test
    @SuppressWarnings("unchecked")
    void shouldRenderTheErrorObjectWithItsHintAndDetails() {
        GtaException e = new GtaException(ErrorCode.POLICY_SPEC_INVALID, "2 lint issues", "Fix them and submit again")
                .detail("issues", List.of("a", "b"));

        Map<String, Object> error = (Map<String, Object>) e.toErrorObject().get("error");

        assertEquals("POLICY_SPEC_INVALID", error.get("code"));
        assertEquals("policy", error.get("category"));
        assertEquals("2 lint issues", error.get("message"));
        assertEquals("Fix them and submit again", error.get("hint"));
        assertEquals(false, error.get("retryable"));
        assertEquals(Map.of("issues", List.of("a", "b")), error.get("details"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldLeaveOutEmptyDetailsAndKeepTheCause() {
        IOException cause = new IOException("disk full");
        GtaException e = new GtaException(ErrorCode.STATE_CORRUPT, "Cannot read state.json", "Run ./gtaw next", cause);

        Map<String, Object> error = (Map<String, Object>) e.toErrorObject().get("error");

        assertFalse(error.containsKey("details"));
        assertEquals(true, error.get("retryable"), "state errors may be retried");
        assertEquals(cause, e.getCause());
    }
}
