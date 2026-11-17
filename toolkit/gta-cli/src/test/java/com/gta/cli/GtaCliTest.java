package com.gta.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.error.ErrorCode;
import com.gta.core.run.RunResult;
import com.gta.core.util.ProcessRunner;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** gtaw's command-line contract (plan §5.6): JSON on stdout, one error object per failure, the category's exit code. */
class GtaCliTest {
    @TempDir
    Path root;

    private record Run(int exit, String out) {
        /** The output without whitespace: gtaw prints pretty JSON. */
        String compact() {
            return out.replaceAll("\\s", "");
        }
    }

    private Run gtaw(String... args) {
        PrintStream before = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            int exit = GtaCli.commandLine().execute(args);
            return new Run(exit, buf.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(before);
        }
    }

    private void gitRepo() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><artifactId>svc</artifactId></project>");
        for (List<String> cmd : List.of(
                List.of("git", "init", "-q"),
                List.of("git", "config", "user.email", "t@example.com"),
                List.of("git", "config", "user.name", "t"))) {
            ProcessRunner.run(root, cmd);
        }
    }

    @Test
    void shouldPrintItsVersionAndUsage() {
        assertEquals(0, gtaw("--version").exit());
        assertTrue(gtaw().out().contains("Usage: gtaw"), "no command prints the usage");
    }

    @Test
    void shouldAnswerStatusAndCasesAsJson() throws Exception {
        gitRepo();

        Run status = gtaw("--root", root.toString(), "status");
        Run human = gtaw("--root", root.toString(), "--format", "human", "status");
        Run cases = gtaw("--root", root.toString(), "cases");

        assertEquals(0, status.exit());
        assertTrue(status.out().strip().startsWith("{") && status.compact().contains("\"items\""), status.out());
        assertTrue(human.out().contains("items: none"), human.out());
        assertTrue(cases.compact().contains("\"index\":\".gta/specs/INDEX.md\""), cases.out());
    }

    @Test
    void shouldReportFailuresAsOneErrorObjectWithTheCategoryExitCode() throws Exception {
        Run notARepo = gtaw("--root", root.toString(), "next");
        Run badOption = gtaw("--root", root.toString(), "status", "--bogus");
        Run unavailable = gtaw("--root", root.toString(), "env");
        Run noIds = gtaw("--root", root.toString(), "cases", "confirm-retired");

        assertEquals(30, notARepo.exit(), notARepo.out());
        assertTrue(notARepo.compact().contains("\"code\":\"ENV_NOT_A_GIT_REPO\""), notARepo.out());
        assertEquals(20, badOption.exit());
        assertTrue(badOption.compact().contains("\"code\":\"USAGE_INVALID_ARGUMENT\""), badOption.out());
        assertTrue(
                badOption.out().contains("./gtaw status --help"),
                "the failing command, not --root: " + badOption.out());
        assertEquals(20, unavailable.exit(), "deployed environments are out of scope");
        assertEquals(20, noIds.exit(), "deleting tests needs explicit ids or --all");
    }

    @Test
    void shouldExitWithZeroOnlyWhenTheRunFinishedAndEveryTestPassed() {
        RunResult passed = new RunResult();
        RunResult compileError = new RunResult();
        compileError.compileError = true;
        compileError.exitCode = 1;
        RunResult timedOut = new RunResult();
        timedOut.timedOut = true;
        RunResult brokenBuild = new RunResult(); // e.g. a dependency that cannot be resolved: no report at all
        brokenBuild.exitCode = 1;

        assertEquals(0, GtaCli.Run.runExitCode(passed));
        assertEquals(ErrorCode.BUILD_COMPILE_ERROR.exitCode(), GtaCli.Run.runExitCode(compileError));
        assertEquals(ErrorCode.TEST_TIMEOUT.exitCode(), GtaCli.Run.runExitCode(timedOut));
        assertEquals(ErrorCode.ENV_TEST_REPORTS_UNUSABLE.exitCode(), GtaCli.Run.runExitCode(brokenBuild));
    }

    @Test
    void shouldReadAReplyFileSavedWithAByteOrderMark() throws Exception {
        Path reply = root.resolve("reply.txt");
        Files.writeString(reply, "\uFEFFapprove order.create@R4\r\n", StandardCharsets.UTF_8);

        assertEquals("approve order.create@R4", GtaCli.Answer.replyFile(reply), "Notepad's BOM is not part of it");
    }
}
