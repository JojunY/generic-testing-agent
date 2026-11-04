package com.gta.core.util;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class ProcessRunnerTest {
    @TempDir
    Path dir;

    @Test
    void shouldCaptureOutputAndExitCode() {
        ProcessRunner.Result r = ProcessRunner.run(dir, ProcessRunner.cmd("git", "--version"));

        assertTrue(r.ok());
        assertTrue(r.output().startsWith("git version"), r.output());
        assertFalse(r.timedOut());
    }

    @Test
    void shouldPassEnvironmentVariablesAndRemoveNullOnes() {
        assumeFalse(ProcessRunner.isWindows());
        Map<String, String> env = new java.util.HashMap<>();
        env.put("GTA_TEST_VALUE", "set-by-test");
        env.put("HOME", null);

        ProcessRunner.Result r = ProcessRunner.run(dir, Duration.ofSeconds(30), env,
                ProcessRunner.cmd("sh", "-c", "echo \"$GTA_TEST_VALUE:${HOME:-unset}\""));

        assertEquals("set-by-test:unset", r.output().strip());
    }

    @Test
    void shouldKeepTheOutputOfAProcessThatTimesOut() {
        assumeFalse(ProcessRunner.isWindows());

        ProcessRunner.Result r = ProcessRunner.run(dir, Duration.ofMillis(500), Map.of(),
                ProcessRunner.cmd("sh", "-c", "echo started; sleep 30"));

        assertTrue(r.timedOut());
        assertFalse(r.ok());
        assertEquals(ProcessRunner.TIMED_OUT, r.exitCode());
        assertTrue(r.output().contains("started"), "the log shows how far the process got: " + r.output());
        assertTrue(r.output().contains("timed out"), r.output());
    }

    @Test
    void shouldReportAToolThatIsNotInstalled() {
        GtaException e = assertThrows(GtaException.class,
                () -> ProcessRunner.run(dir, List.of("gta-no-such-tool-" + System.nanoTime())));

        assertEquals(ErrorCode.ENV_TOOL_UNAVAILABLE, e.code());
    }
}
