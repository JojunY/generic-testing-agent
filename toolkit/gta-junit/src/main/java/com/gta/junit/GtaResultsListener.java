package com.gta.junit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Collectors;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/**
 * Writes one JSON line per executed test (class, method, tags, display name, outcome) to
 * {@code target/gta-results/} so gtaw can map results to scenarios. Registered through the
 * JUnit Platform ServiceLoader, so adding gta-junit as a test dependency is enough.
 */
public final class GtaResultsListener implements TestExecutionListener {
    private final Path file;

    public GtaResultsListener() {
        String dir = System.getProperty("gta.results.dir", "target/gta-results");
        this.file = Path.of(dir).resolve("results-" + ProcessHandle.current().pid() + ".jsonl");
    }

    @Override
    public void executionSkipped(TestIdentifier id, String reason) {
        if (id.isTest()) {
            write(id, "skipped", reason);
        }
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (!id.isTest()) {
            return;
        }
        String message = result.getThrowable().map(Throwable::getMessage).orElse(null);
        String outcome =
                switch (result.getStatus()) {
                    case SUCCESSFUL -> "passed";
                    case ABORTED -> message != null && message.startsWith(KnownDefectExtension.ABORT_PREFIX)
                            ? "known-defect"
                            : "skipped";
                    case FAILED -> "failed";
                };
        write(id, outcome, message);
    }

    private void write(TestIdentifier id, String outcome, String message) {
        if (!(id.getSource().orElse(null) instanceof MethodSource m)) {
            return;
        }
        String tags = id.getTags().stream()
                .map(TestTag::getName)
                .map(GtaResultsListener::quote)
                .collect(Collectors.joining(","));
        String line = "{\"className\":" + quote(m.getClassName()) + ",\"method\":" + quote(m.getMethodName())
                + ",\"displayName\":" + quote(id.getDisplayName()) + ",\"tags\":[" + tags + "],\"outcome\":"
                + quote(outcome)
                + ",\"message\":" + quote(message) + "}\n";
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // results are an optimisation for gtaw; never fail the build because of them
        }
    }

    static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
