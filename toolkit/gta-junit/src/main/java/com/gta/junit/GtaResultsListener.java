package com.gta.junit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.ClasspathResourceSource;
import org.junit.platform.engine.support.descriptor.FileSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Writes one JSON line per executed test (class, method, tags, display name, outcome) to
 * {@code target/gta-results/} so gtaw can map results to scenarios. Registered through the
 * JUnit Platform ServiceLoader, so adding gta-junit as a test dependency is enough. A Cucumber scenario (a test whose
 * source is its feature file) is written too when it carries a {@code scenario:} tag, with its feature file and the
 * verdict on its pin ({@link KnownDefects}).
 */
public final class GtaResultsListener implements TestExecutionListener {
    private final Path file;
    private TestPlan plan;

    public GtaResultsListener() {
        String dir = System.getProperty("gta.results.dir", "target/gta-results");
        this.file = Paths.get(dir).resolve("results-" + pid() + ".jsonl");
    }

    /** One file per test JVM, so parallel forks never interleave lines (ProcessHandle needs Java 9). */
    private static String pid() {
        String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at > 0 ? name.substring(0, at) : Integer.toHexString(System.identityHashCode(name));
    }

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        this.plan = testPlan;
    }

    @Override
    public void executionSkipped(TestIdentifier id, String reason) {
        if (id.isTest()) {
            write(id, "skipped", reason, false);
        }
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (!id.isTest()) {
            return;
        }
        String message = result.getThrowable().map(Throwable::getMessage).orElse(null);
        String outcome;
        switch (result.getStatus()) {
            case SUCCESSFUL:
                outcome = "passed";
                break;
            case ABORTED:
                outcome = message != null && message.startsWith(KnownDefectExtension.ABORT_PREFIX)
                        ? "known-defect"
                        : "skipped";
                break;
            default:
                outcome = "failed";
        }
        // a pinned Cucumber scenario that ran (a gtaw run): a known defect only while it fails as recorded
        List<String> tags = tagNames(id);
        String pinned = isMethod(id) ? null : KnownDefects.fingerprint(tags);
        if (pinned != null && !"known-defect".equals(outcome) && !"skipped".equals(outcome)) {
            if ("passed".equals(outcome)) {
                outcome = "failed";
                message = "Known defect no longer reproduces: the defect looks fixed. Remove the @known-defect tags so "
                        + "the scenario becomes a regular regression test.";
            } else {
                Throwable t = result.getThrowable().orElse(null);
                String actual = t == null ? "" : Fingerprint.of(t);
                if (pinned.equals(actual)) {
                    outcome = "known-defect";
                    message = KnownDefectExtension.ABORT_PREFIX + " still failing as recorded: " + actual;
                } else {
                    message = "Known defect now fails differently (fingerprint " + actual + ", expected " + pinned
                            + "): " + message;
                }
            }
        }
        write(id, outcome, message, pinned != null);
    }

    private static boolean isMethod(TestIdentifier id) {
        return id.getSource().orElse(null) instanceof MethodSource;
    }

    private static List<String> tagNames(TestIdentifier id) {
        List<String> out = new ArrayList<>();
        for (TestTag t : id.getTags()) {
            out.add(t.getName());
        }
        return out;
    }

    private void write(TestIdentifier id, String outcome, String message, boolean pinned) {
        Object source = id.getSource().orElse(null);
        String className;
        String method;
        String feature = null;
        if (source instanceof MethodSource) {
            MethodSource m = (MethodSource) source;
            className = m.getClassName();
            method = m.getMethodName();
        } else if ((source instanceof ClasspathResourceSource || source instanceof FileSource)
                && tagNames(id).stream().anyMatch(t -> t.startsWith("scenario:"))) {
            // a Cucumber scenario: reports name it by its scenario name, under the suite class that ran it
            className = suiteClass(id);
            method = id.getLegacyReportingName();
            feature = source instanceof ClasspathResourceSource
                    ? ((ClasspathResourceSource) source).getClasspathResourceName()
                    : ((FileSource) source).getFile().getPath();
        } else {
            return;
        }
        String tags = id.getTags().stream()
                .map(TestTag::getName)
                .map(GtaResultsListener::quote)
                .collect(Collectors.joining(","));
        String line = "{\"className\":" + quote(className) + ",\"method\":" + quote(method)
                + ",\"displayName\":" + quote(id.getDisplayName()) + ",\"tags\":[" + tags + "],\"outcome\":"
                + quote(outcome)
                + ",\"message\":" + quote(message)
                + (feature == null ? "" : ",\"feature\":" + quote(feature))
                + (pinned ? ",\"pinned\":true" : "")
                + "}\n";
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Files.write(
                    file, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // results are an optimisation for gtaw; never fail the build because of them
        }
    }

    /** The class of the nearest ancestor with a class source (the JUnit Platform suite), or "". */
    private String suiteClass(TestIdentifier id) {
        TestIdentifier at = id;
        while (plan != null && at != null) {
            TestSource s = at.getSource().orElse(null);
            if (s instanceof ClassSource) {
                return ((ClassSource) s).getClassName();
            }
            at = plan.getParent(at).orElse(null);
        }
        return "";
    }

    static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
