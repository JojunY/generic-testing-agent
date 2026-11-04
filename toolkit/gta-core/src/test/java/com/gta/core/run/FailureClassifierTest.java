package com.gta.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.gta.core.error.ErrorCode;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FailureClassifierTest {
    static final Set<String> PROD = Set.of("com.acme.order.OrderService");
    static final String GTA_JUNIT_SAMPLE = "6a1f76173bb3";
    static final Set<String> TESTS = Set.of("com.acme.order.OrderIT");

    private static TestOutcome failing(String outcome, String type, String stack) {
        TestOutcome c = new TestOutcome();
        c.outcome = outcome;
        c.failureType = type;
        c.stack = stack;
        return c;
    }

    @Test
    void shouldClassifyAnAssertionFailure() {
        TestOutcome c = failing(
                "failed",
                "java.lang.AssertionError",
                "java.lang.AssertionError: x\n\tat com.acme.order.OrderIT.should(OrderIT.java:10)");

        FailureClassifier.classify(c, PROD, TESTS);

        assertEquals(ErrorCode.TEST_ASSERTION_FAILED.name(), c.code);
    }

    @Test
    void shouldBlameProductionCodeByTheFirstNonFrameworkFrame() {
        TestOutcome c = failing(
                "error",
                "java.lang.NullPointerException",
                "java.lang.NullPointerException\n\tat org.springframework.aop.Foo.invoke(Foo.java:1)\n"
                        + "\tat com.acme.order.OrderService.create(OrderService.java:20)\n\tat com.acme.order.OrderIT.should(OrderIT.java:10)");

        FailureClassifier.classify(c, PROD, TESTS);

        assertEquals(ErrorCode.TEST_SUT_ERROR.name(), c.code);
    }

    @Test
    void shouldBlameTestCodeWhenOnlyTheTestIsInTheChain() {
        TestOutcome c = failing(
                "error",
                "java.lang.IllegalStateException",
                "java.lang.IllegalStateException\n\tat com.acme.order.OrderIT.setUp(OrderIT.java:5)");

        FailureClassifier.classify(c, PROD, TESTS);

        assertEquals(ErrorCode.TEST_CODE_ERROR.name(), c.code);
    }

    @Test
    void shouldIgnoreVolatileDetailsInTheFingerprint() {
        String a = FailureClassifier.fingerprint(
                "TEST_ASSERTION_FAILED",
                "expected 10 but was 0 at 2026-11-03T10:00:00Z id 3f2b1c9e-0000-4000-8000-000000000001");
        String b = FailureClassifier.fingerprint(
                "TEST_ASSERTION_FAILED",
                "expected 10 but was 0 at 2026-11-04T11:30:00Z id 7a1b2c3d-0000-4000-8000-000000000002");
        String c = FailureClassifier.fingerprint(
                "TEST_ASSERTION_FAILED",
                "expected 10 but was 5 at 2026-11-04T11:30:00Z id 7a1b2c3d-0000-4000-8000-000000000002");

        assertEquals(a, b);
        assertNotEquals(a, c, "a different actual value is a different failure");
    }

    @Test
    void shouldMatchTheGtaJunitFingerprintAlgorithm() {
        // value computed with com.gta.junit.Fingerprint.of("java.lang.AssertionError", "total expected 90.00 but was
        // 100.00")
        assertEquals(
                GTA_JUNIT_SAMPLE,
                FailureClassifier.knownDefectFingerprint(
                        "java.lang.AssertionError", "total expected 90.00 but was 100.00"));
    }

    @Test
    void shouldNotMistakeAServiceUnderAFrameworkLikePackageForFramework() {
        Set<String> prod = Set.of("org.apache.billing.InvoiceService");
        TestOutcome c = failing(
                "error",
                "java.lang.IllegalStateException",
                "java.lang.IllegalStateException: no rate\n\tat org.apache.billing.InvoiceService.total(InvoiceService.java:42)\n"
                        + "\tat org.apache.billing.InvoiceIT.shouldTotal(InvoiceIT.java:12)");

        FailureClassifier.classify(c, prod, Set.of("org.apache.billing.InvoiceIT"));

        assertEquals(ErrorCode.TEST_SUT_ERROR.name(), c.code, "the failure came from the service's own code");
    }
}
