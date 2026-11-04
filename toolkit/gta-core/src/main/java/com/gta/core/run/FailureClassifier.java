package com.gta.core.run;

import com.gta.core.error.ErrorCode;
import com.gta.core.util.Hashes;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies a failing test by its cause chain (plan §5.3): the first non-framework stack frame decides whether
 * the failure came from production code (triage), from test code (repair) or happened before the SUT was reached.
 */
public final class FailureClassifier {
    private static final Pattern FRAME = Pattern.compile("^\\s*at\\s+([\\w$.]+)\\.([\\w$<>]+)\\(", Pattern.MULTILINE);
    private static final List<String> FRAMEWORK = List.of(
            "java.",
            "javax.",
            "jakarta.",
            "jdk.",
            "sun.",
            "com.sun.",
            "org.junit.",
            "org.opentest4j.",
            "org.assertj.",
            "org.hamcrest.",
            "org.mockito.",
            "net.bytebuddy.",
            "org.springframework.",
            "org.apache.",
            "org.awaitility.",
            "io.restassured.",
            "com.github.tomakehurst.",
            "org.wiremock.",
            "com.fasterxml.",
            "org.hibernate.",
            "com.zaxxer.",
            "kotlin.",
            "reactor.",
            "io.netty.",
            "com.gta.junit.",
            "org.h2.",
            "oracle.",
            "com.google.",
            "io.grpc.",
            "org.slf4j.",
            "ch.qos.");

    private FailureClassifier() {}

    public static void classify(TestOutcome c, Set<String> productionClasses, Set<String> testClasses) {
        if (c.passed() || "skipped".equals(c.outcome)) {
            return;
        }
        String type = c.failureType == null ? "" : c.failureType;
        String stack = c.stack == null ? "" : c.stack;
        String firstApp = firstNonFramework(stack);
        boolean productionInChain = anyProductionFrame(stack, productionClasses);
        c.sutReached = productionInChain || "failed".equals(c.outcome);

        if (type.contains("ConditionTimeoutException")
                || type.endsWith("TimeoutException")
                || stack.contains("ConditionTimeoutException")) {
            c.code = ErrorCode.TEST_TIMEOUT.name();
        } else if ("failed".equals(c.outcome)
                || type.contains("AssertionError")
                || type.contains("AssertionFailedError")
                || stack.contains("Caused by: java.lang.AssertionError")
                || stack.contains("Caused by: org.opentest4j.AssertionFailedError")) {
            c.code = ErrorCode.TEST_ASSERTION_FAILED.name();
        } else if (firstApp != null && productionClasses.contains(outerClass(firstApp))) {
            c.code = ErrorCode.TEST_SUT_ERROR.name();
        } else if (firstApp != null && testClasses.contains(outerClass(firstApp))) {
            // thrown by test code - unless the root cause sits in production code (e.g. context startup)
            c.code = productionInChain && stack.contains("Caused by:")
                    ? ErrorCode.TEST_SUT_ERROR.name()
                    : ErrorCode.TEST_CODE_ERROR.name();
        } else if (productionInChain) {
            c.code = ErrorCode.TEST_SUT_ERROR.name();
        } else if (firstApp == null) {
            c.code = ErrorCode.TEST_SETUP_ERROR.name();
        } else {
            c.code = ErrorCode.TEST_SUT_ERROR.name(); // unknown origin: triage is the safe default
        }
    }

    static String firstNonFramework(String stack) {
        Matcher m = FRAME.matcher(stack);
        while (m.find()) {
            String cls = m.group(1);
            if (FRAMEWORK.stream().noneMatch(cls::startsWith)) {
                return cls;
            }
        }
        return null;
    }

    static boolean anyProductionFrame(String stack, Set<String> productionClasses) {
        Matcher m = FRAME.matcher(stack);
        while (m.find()) {
            if (productionClasses.contains(outerClass(m.group(1)))) {
                return true;
            }
        }
        return false;
    }

    static String outerClass(String cls) {
        int dollar = cls.indexOf('$');
        return dollar > 0 ? cls.substring(0, dollar) : cls;
    }

    /** Same as com.gta.junit.Fingerprint.VOLATILE (gta-core does not depend on gta-junit). */
    static final String VOLATILE =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2})?|0x[0-9a-fA-F]+|@[0-9a-fA-F]{4,}\\b|\\b[0-9a-f]{8,}\\b|\\b\\d{6,}\\b";

    /** Same algorithm as com.gta.junit.Fingerprint, so @KnownDefect(failure=...) can be compared at test time. */
    public static String knownDefectFingerprint(String exceptionType, String message) {
        String m = message == null
                ? ""
                : message.replaceAll(VOLATILE, "#").replaceAll("\\s+", " ").trim();
        return Hashes.sha256((exceptionType == null ? "" : exceptionType) + "|" + m)
                .substring(0, 12);
    }

    /** Stable fingerprint of a failure: code + message with volatile parts normalised. */
    public static String fingerprint(String code, String message) {
        String m = message == null
                ? ""
                : message.replaceAll(VOLATILE, "#").replaceAll("\\s+", " ").trim();
        return Hashes.shortSha(code + "|" + m);
    }
}
