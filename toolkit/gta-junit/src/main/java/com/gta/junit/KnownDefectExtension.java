package com.gta.junit;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.opentest4j.TestAbortedException;

/** Implements {@link KnownDefect}. */
public final class KnownDefectExtension implements TestExecutionExceptionHandler, AfterTestExecutionCallback {
    public static final String ABORT_PREFIX = "gta-known-defect:";
    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(KnownDefectExtension.class);

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        KnownDefect kd = context.getRequiredTestMethod().getAnnotation(KnownDefect.class);
        if (kd == null || throwable instanceof TestAbortedException) {
            throw throwable;
        }
        context.getStore(NS).put("failed", Boolean.TRUE);
        String actual = Fingerprint.of(throwable);
        if (!kd.failure().isEmpty() && !kd.failure().equals(actual)) {
            AssertionError e = new AssertionError("Known defect " + kd.id() + " now fails differently (fingerprint "
                    + actual + ", expected " + kd.failure() + "): " + throwable);
            e.initCause(throwable);
            throw e;
        }
        throw new TestAbortedException(ABORT_PREFIX + kd.id() + " (still failing as recorded: " + actual + ")");
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        KnownDefect kd = context.getRequiredTestMethod().getAnnotation(KnownDefect.class);
        if (kd == null) {
            return;
        }
        boolean failed = Boolean.TRUE.equals(context.getStore(NS).get("failed"))
                || context.getExecutionException().isPresent();
        if (!failed) {
            throw new AssertionError("Known defect " + kd.id() + " no longer reproduces: the defect looks fixed. "
                    + "Remove @KnownDefect so the test becomes a regular regression test.");
        }
    }
}
