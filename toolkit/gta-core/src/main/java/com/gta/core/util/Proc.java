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
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs external processes (git, mvn) with a timeout and captured output. */
public final class Proc {
    public record Result(int exitCode, String output) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    private Proc() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static Result run(Path dir, Duration timeout, Map<String, String> env, List<String> command) {
        try {
            Path log = Files.createTempFile("gta-proc", ".log");
            try {
                ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
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
                if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                    throw new GtaException(ErrorCode.TRANSIENT_PROCESS_TIMEOUT,
                            "Command timed out after " + timeout.toMinutes() + " min: " + String.join(" ", command),
                            "Raise budgets.nodeTimeoutMinutes in .gta/config.json or make the test faster");
                }
                return new Result(p.exitValue(), Files.readString(log, StandardCharsets.UTF_8));
            } finally {
                Files.deleteIfExists(log);
            }
        } catch (IOException e) {
            throw new GtaException(ErrorCode.ENV_MAVEN_UNAVAILABLE, "Cannot start " + command.get(0) + ": " + e.getMessage(),
                    "Make sure the tool is installed and on PATH; ./gtaw doctor checks this");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GtaException(ErrorCode.INTERNAL_ERROR, "Interrupted", "Re-run the command");
        }
    }

    public static Result run(Path dir, List<String> command) {
        return run(dir, Duration.ofMinutes(5), null, command);
    }

    public static List<String> cmd(String... parts) {
        return new ArrayList<>(List.of(parts));
    }
}
