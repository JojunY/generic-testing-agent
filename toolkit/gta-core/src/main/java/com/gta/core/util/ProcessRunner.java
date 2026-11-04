package com.gta.core.util;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs external processes (git, mvn) with a timeout and captured output. A process that exceeds its timeout is killed
 * with its children; the result keeps the output written so far and says {@link Result#timedOut()}.
 */
public final class ProcessRunner {
    /** Exit code reported for a process that was killed at its timeout. */
    public static final int TIMED_OUT = -1;

    public record Result(int exitCode, String output, boolean timedOut) {
        public Result(int exitCode, String output) {
            this(exitCode, output, false);
        }

        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }
    }

    private ProcessRunner() {}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static Result run(Path dir, Duration timeout, Map<String, String> env, List<String> command) {
        Path log = null;
        try {
            log = Files.createTempFile("gta-proc", ".log");
            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            if (env != null) {
                env.forEach((k, v) -> {
                    if (v == null) {
                        pb.environment().remove(k);
                    } else {
                        pb.environment().put(k, v);
                    }
                });
            }
            Process p = pb.start();
            if (p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return new Result(p.exitValue(), Files.readString(log, StandardCharsets.UTF_8));
            }
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            p.waitFor(10, TimeUnit.SECONDS); // let the OS release the log file (Windows keeps it locked until exit)
            String output = Files.readString(log, StandardCharsets.UTF_8) + "\n[gtaw] timed out after "
                    + timeout.toMinutes() + " min: " + String.join(" ", command) + "\n";
            return new Result(TIMED_OUT, output, true);
        } catch (IOException e) {
            throw new GtaException(
                    ErrorCode.ENV_TOOL_UNAVAILABLE,
                    "Cannot start " + command.get(0) + ": " + e.getMessage(),
                    "Make sure the tool is installed and on PATH; ./gtaw doctor checks this");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GtaException(ErrorCode.INTERNAL_ERROR, "Interrupted", "Re-run the command");
        } finally {
            deleteQuietly(log);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            file.toFile().deleteOnExit(); // still held by a dying process: removed when gtaw exits
        }
    }

    public static Result run(Path dir, List<String> command) {
        return run(dir, Duration.ofMinutes(5), null, command);
    }

    public static List<String> cmd(String... parts) {
        return new ArrayList<>(List.of(parts));
    }
}
