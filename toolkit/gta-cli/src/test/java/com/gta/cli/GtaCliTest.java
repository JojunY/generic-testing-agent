package com.gta.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.run.RunResult;
import com.gta.core.run.TestOutcome;
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
        assertEquals(20, unavailable.exit(), "a later phase");
        assertEquals(20, noIds.exit(), "deleting tests needs explicit ids or --all");
    }

    @Test
    void shouldReadAReplyFileSavedWithAByteOrderMark() throws Exception {
        Path reply = root.resolve("reply.txt");
        Files.writeString(reply, "\uFEFFapprove order.create@R4\r\n", StandardCharsets.UTF_8);

        assertEquals("approve order.create@R4", GtaCli.Answer.replyFile(reply), "Notepad's BOM is not part of it");
    }

    @Test
    void shouldPassAWordStartingWithAnAtSignVerbatim() throws Exception {
        Path team = root.resolve("team");
        Files.writeString(team, "owns refunds");

        List<String> words =
                GtaCli.commandLine().parseArgs("note", "@" + team).subcommand().matchedPositionalValue(0, List.of());

        assertEquals(List.of("@" + team), words, "not the contents of the file it names");
    }

    @Test
    void shouldExitWithTheBuildCodeWhenTheTestsDoNotCompile() {
        RunResult broken = new RunResult();
        broken.compileError = true;
        RunResult failing = new RunResult();
        TestOutcome failed = new TestOutcome();
        failed.outcome = "failed";
        failing.cases.add(failed);

        assertEquals(40, GtaCli.Run.exitCode(broken), "BUILD_COMPILE_ERROR");
        assertEquals(10, GtaCli.Run.exitCode(failing), "TEST_ASSERTION_FAILED");
        assertEquals(0, GtaCli.Run.exitCode(new RunResult()));
    }

    @Test
    void shouldReportAMissingReplyFileOrAnUnreadableWaiversFileAsTheUsersToFix() throws Exception {
        gitRepo();
        Files.createDirectories(root.resolve(".gta"));
        Files.writeString(root.resolve(".gta/waivers.json"), "{bad");

        Run noFile = gtaw(
                "--root",
                root.toString(),
                "answer",
                "--file",
                root.resolve("nosuch.txt").toString());
        Run badWaivers = gtaw(
                "--root",
                root.toString(),
                "gate",
                "waive",
                "GATE_SCENARIO_REGRESSED",
                "--target",
                "*",
                "--reason",
                "r",
                "--expires",
                java.time.LocalDate.now().plusDays(1).toString());

        assertEquals(20, noFile.exit(), noFile.out());
        assertEquals(21, badWaivers.exit(), badWaivers.out());
    }
}
