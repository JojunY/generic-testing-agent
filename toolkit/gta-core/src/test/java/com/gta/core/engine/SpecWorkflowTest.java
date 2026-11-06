package com.gta.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.run.TestRunnerFake;
import com.gta.core.spec.SpecParser;
import com.gta.core.util.ProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The spec loop (plan §3): analyst submissions, question rounds, defaults, owned fields and the submission budget. */
class SpecWorkflowTest {
    static final Path REPO = Path.of("../..").toAbsolutePath().normalize();
    static final Path FIXTURE = REPO.resolve("evals/fixtures/order-service");
    static final String FLOW = "order.create-order";

    @TempDir
    Path root;

    GtaPaths paths;
    String token;

    private ProcessRunner.Result git(String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return ProcessRunner.run(root, Duration.ofSeconds(30), Map.of(), cmd);
    }

    private Engine engine() {
        return new Engine(root, new TestRunnerFake());
    }

    private String scripted() throws Exception {
        return Files.readString(REPO.resolve("evals/e2e/scripted-output/create-order.spec.md"));
    }

    @BeforeEach
    void orderService() throws Exception {
        paths = new GtaPaths(root);
        try (var files = Files.walk(FIXTURE)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Path rel = FIXTURE.relativize(f);
                if (!rel.startsWith("target")) {
                    Files.createDirectories(root.resolve(rel).getParent());
                    Files.copy(f, root.resolve(rel), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Files.writeString(root.resolve(".gitignore"), "target/\n.gta/work/\n");
        git("init", "-q");
        git("config", "user.email", "t@example.com");
        git("config", "user.name", "t");
        git("add", "-A");
        git("commit", "-qm", "fixture");
        Map<String, Object> analyze = engine().next("flow", FLOW, null, false);
        assertEquals("analyze", analyze.get("node"));
        token = String.valueOf(analyze.get("token"));
        Files.createDirectories(paths.spec(FLOW).getParent());
    }

    private GtaException submitFails(String spec) throws Exception {
        Files.writeString(paths.spec(FLOW), spec);
        return assertThrows(GtaException.class, () -> engine().submitSpec(FLOW, token));
    }

    @Test
    void shouldRefuseASubmissionWithoutASpecFile() {
        GtaException e = assertThrows(GtaException.class, () -> engine().submitSpec(FLOW, token));

        assertEquals(ErrorCode.USAGE_UNKNOWN_FLOW, e.code());
    }

    @Test
    void shouldRefuseAStaleToken() throws Exception {
        Files.writeString(paths.spec(FLOW), scripted());

        assertEquals(
                ErrorCode.STATE_STALE_SESSION,
                assertThrows(GtaException.class, () -> engine().submitSpec(FLOW, "old"))
                        .code());
    }

    @Test
    void shouldRevertAnAnalystsEditOutsideTheSpec() throws Exception {
        Path app = root.resolve("src/main/java/com/example/order/OrderApplication.java");
        Files.writeString(app, Files.readString(app) + "// tweak\n");

        GtaException e = submitFails(scripted());

        assertEquals(ErrorCode.POLICY_OUT_OF_SCOPE_CHANGE, e.code());
        assertTrue(git("diff", "--quiet", "--", "src/main").ok(), "the production file was restored");
    }

    @Test
    void shouldRefuseFieldsOnlyGtawMayWrite() throws Exception {
        Files.writeString(paths.spec(FLOW), scripted());
        engine().submitSpec(FLOW, token);
        token = String.valueOf(engine().next(null, null, null, false).get("token"));
        String spec = Files.readString(paths.spec(FLOW));

        GtaException e = submitFails(spec.replace("status=in-review", "status=approved"));

        assertTrue(
                e.code() == ErrorCode.POLICY_GTA_OWNED_FIELD || e.code() == ErrorCode.STATE_STALE_SESSION,
                e.code().name());
    }

    @Test
    void shouldReturnLintIssuesAndEscalateAfterTheSubmissionBudget() throws Exception {
        String broken = scripted().replace("SHALL store it", "stores it");

        for (int i = 0; i < 3; i++) {
            GtaException e = submitFails(broken);
            assertEquals(ErrorCode.POLICY_SPEC_INVALID, e.code());
            assertTrue(e.details().toString().contains("EARS"), e.details().toString());
        }

        Map<String, Object> next = engine().next(null, null, null, false);
        assertEquals("escalate", next.get("node"), "spec.maxSubmitAttempts is 3");
    }

    @Test
    void shouldDowngradeADocumentedSourceThatCannotBeVerified() throws Exception {
        Files.writeString(
                paths.spec(FLOW),
                scripted()
                        .replace(
                                "id=order.create-order/s01 source=observed",
                                "id=order.create-order/s01 source=documented doc=README.md#orders quote=\"orders are free\""));

        Map<String, Object> out = engine().submitSpec(FLOW, token);

        assertTrue(out.get("notes").toString().contains("s01 downgraded to observed"), out.toString());
        assertTrue(SpecParser.parse(paths.spec(FLOW))
                .scenario(FLOW + "/s01")
                .orElseThrow()
                .source()
                .equals("observed"));
    }

    @Test
    void shouldNoteScenariosWithoutAPriorityAndStillAcceptTheSpec() throws Exception {
        Files.writeString(paths.spec(FLOW), scripted());

        Map<String, Object> out = engine().submitSpec(FLOW, token);

        assertTrue(
                out.get("notes").toString().contains(FLOW + "/s01 has no priority=; it counts as P1"), out.toString());
    }

    @Test
    void shouldCheckTheRoundOfAnAnswer() throws Exception {
        Files.writeString(paths.spec(FLOW), scripted());
        engine().submitSpec(FLOW, token);
        engine().next(null, null, null, false);

        assertEquals(
                ErrorCode.USAGE_UNPARSEABLE_ANSWER,
                assertThrows(GtaException.class, () -> engine().answer("R7: 1A"))
                        .code());
        assertEquals(
                ErrorCode.USAGE_UNPARSEABLE_ANSWER,
                assertThrows(GtaException.class, () -> engine().answer("approve " + FLOW + "@R1"))
                        .code(),
                "an approval is not an answer to open questions");
        engine().answer("R1: 1A");
        engine().answer("approve " + FLOW + "@R2");
        assertEquals(
                ErrorCode.STATE_NOTHING_PENDING,
                assertThrows(GtaException.class, () -> engine().answer("R1: 1A"))
                        .code(),
                "answers are only accepted while a question is open");
    }

    @Test
    void shouldKeepAHighImpactQuestionOpenOnRestDefault() throws Exception {
        Files.writeString(paths.spec(FLOW), scripted());
        engine().submitSpec(FLOW, token);
        engine().next(null, null, null, false);

        Map<String, Object> out = engine().answer("R1: rest default");

        assertTrue(String.valueOf(out.get("result")).contains("high impact needs an explicit answer"), out.toString());
    }

    @Test
    void shouldApproveDirectlyWhenTheAnswerKeepsTheCurrentCode() throws Exception {
        Files.writeString(paths.spec(FLOW), scripted());
        engine().submitSpec(FLOW, token);
        engine().next(null, null, null, false);

        Map<String, Object> out = engine().answer("R1: 1A");

        assertTrue(String.valueOf(out.get("result")).contains("spec is now " + FLOW + "@R2"), out.toString());
        assertEquals("approve", engine().next(null, null, null, false).get("type"));
        assertTrue(Files.readString(paths.spec(FLOW)).contains("id=order.create-order/s03 source=confirmed"));
    }

    @Test
    void shouldSendAChangeRequestBackToTheAnalyst() throws Exception {
        Files.writeString(paths.spec(FLOW), scripted());
        engine().submitSpec(FLOW, token);
        engine().next(null, null, null, false);
        engine().answer("R1: 1A");
        engine().next(null, null, null, false);

        Map<String, Object> out = engine().answer("not yet: s04 should also cover a negative subtotal");

        assertTrue(String.valueOf(out.get("result")).contains("change"), out.toString());
        assertEquals(
                "analyze", engine().next(null, null, null, false).get("node"), "back to the analyst with the request");
    }

    @Test
    void shouldTakeTheRecommendedAnswersOnOkAndApproveTheShownRevisionOnOk() throws Exception {
        Files.writeString(
                paths.spec(FLOW),
                scripted()
                        .replace(
                                "and total `90.00` (recommended: the Javadoc says \"at least the threshold\")",
                                "and total `90.00`")
                        .replace("(= current code)", "(= current code) (recommended: keep today's behaviour)"));
        engine().submitSpec(FLOW, token);
        Map<String, Object> round = engine().next(null, null, null, false);
        String display = String.valueOf(round.get("display"));
        assertTrue(display.contains("waiting for you"), display);
        assertTrue(display.contains(FLOW + ": spec R1 written"), "what happened: " + display);
        assertTrue(
                display.contains("Needs you\n  ── " + FLOW + " · R1"),
                "the question round is the part that needs you: " + display);

        engine().answer("OK");

        String spec = Files.readString(paths.spec(FLOW));
        assertTrue(
                spec.contains("- [x] A) No discount at exactly 100.00"), "the recommended option is ticked: " + spec);
        assertTrue(spec.contains("A(recommended)"), "the record says the recommendation was taken");
        assertTrue(spec.contains("id=order.create-order/s03 source=confirmed"), "accepting is an explicit answer");
        assertEquals("approve", engine().next(null, null, null, false).get("type"));
        engine().answer("OK");
        assertTrue(Files.readString(paths.spec(FLOW)).contains("status=approved"), "OK approves the shown revision");
    }

    @Test
    void shouldAddAnOwnAnswerAsATickedOptionForTheAnalyst() throws Exception {
        Files.writeString(paths.spec(FLOW), scripted());
        engine().submitSpec(FLOW, token);
        engine().next(null, null, null, false);

        Map<String, Object> out = engine().answer("1: only for loyalty customers, at exactly 100.00");

        String spec = Files.readString(paths.spec(FLOW));
        assertTrue(
                spec.contains("- [x] C) only for loyalty customers, at exactly 100.00 (own answer)"),
                "gtaw adds the words as option C and ticks it: " + spec);
        assertTrue(String.valueOf(out.get("result")).contains("the analyst will now rewrite"), out.toString());
        Map<String, Object> revision = engine().next(null, null, null, false);
        assertEquals("analyze", revision.get("node"));
        assertTrue(Files.readString(root.resolve(String.valueOf(revision.get("brief"))))
                .contains("the person's own answer: only for loyalty customers"));
    }
}
