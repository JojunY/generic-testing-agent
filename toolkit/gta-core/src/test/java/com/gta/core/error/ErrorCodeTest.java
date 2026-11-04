package com.gta.core.error;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Error codes and exit codes as plan §5.6 defines them. */
class ErrorCodeTest {
    @Test
    void shouldFindKnownCodesAndIgnoreUnknownOnes() {
        assertEquals(Optional.of(ErrorCode.TEST_TIMEOUT), ErrorCode.find("TEST_TIMEOUT"));
        assertEquals(Optional.empty(), ErrorCode.find("ENV_MAVEN_UNAVAILABLE"), "a code removed in an older version");
        assertEquals(Optional.empty(), ErrorCode.find(null));
    }

    @Test
    void shouldNameEveryCodeAfterItsCategoryPrefix() {
        for (ErrorCode c : ErrorCode.values()) {
            String prefix = c.name().substring(0, c.name().indexOf('_'));
            String expected = switch (c.category) {
                case TEST_FAILURE -> "TEST";
                case GATE_FAILED, GATE_CONCERN -> "GATE";
                case ENVIRONMENT -> "ENV";
                default -> c.category.name();
            };
            assertEquals(expected, prefix, c.name());
        }
    }

    @Test
    void shouldShareTheExitCodeOfTheCategory() {
        assertEquals(10, ErrorCode.TEST_ASSERTION_FAILED.exitCode());
        assertEquals(0, ErrorCode.GATE_ASSUMED_CLAIM.exitCode(), "gate concerns never fail the build");
        assertEquals(11, ErrorCode.GATE_SCENARIO_REGRESSED.exitCode());
        assertEquals(30, ErrorCode.ENV_GIT_FAILED.exitCode());
        assertEquals(70, ErrorCode.INTERNAL_ERROR.exitCode());
    }

    @Test
    void shouldLetACodeOverrideItsCategoryRetryability() {
        assertTrue(ErrorCode.STATE_LOCKED.retryable());
        assertFalse(ErrorCode.STATE_BUDGET_EXHAUSTED.retryable(), "a used-up budget is not fixed by retrying");
        assertFalse(ErrorCode.POLICY_ORACLE_MODIFIED.retryable());
    }

    @Test
    void shouldSendOnlyAssertionsSutErrorsAndTimeoutsToTriage() {
        assertTrue(ErrorCode.TEST_ASSERTION_FAILED.needsTriage());
        assertTrue(ErrorCode.TEST_SUT_ERROR.needsTriage());
        assertTrue(ErrorCode.TEST_TIMEOUT.needsTriage());
        assertFalse(ErrorCode.TEST_CODE_ERROR.needsTriage());
        assertTrue(ErrorCode.TEST_CODE_ERROR.isMechanical());
        assertTrue(ErrorCode.BUILD_COMPILE_ERROR.isMechanical());
        assertFalse(ErrorCode.TEST_ASSERTION_FAILED.isMechanical());
    }
}
