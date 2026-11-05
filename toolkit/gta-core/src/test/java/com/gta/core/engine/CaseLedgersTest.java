package com.gta.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.oracle.OracleShard;
import com.gta.core.plan.PlanItem;
import com.gta.core.report.CaseLedger;
import com.gta.core.run.RunResult;
import com.gta.core.run.TestOutcome;
import com.gta.core.run.TestRunnerFake;
import com.gta.core.spec.SpecTestData;
import com.gta.core.util.Json;
import com.gta.core.util.ProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Maintenance of the test case ledgers (plan §10.6): who writes which row, and when. */
class CaseLedgersTest {
    static final String S01 = "order.create/s01";
    static final String TEST_CLASS = "com.acme.order.OrderControllerIT";

    @TempDir
    Path root;

    GtaPaths paths;
    EngineContext ctx;

    private ProcessRunner.Result git(String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return ProcessRunner.run(root, Duration.ofSeconds(30), Map.of(), cmd);
    }

    @BeforeEach
    void approvedSpec() throws Exception {
        paths = new GtaPaths(root);
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Files.writeString(root.resolve(".gitignore"), ".gta/work/\n");
        Path controller = root.resolve("src/main/java/com/acme/order/OrderController.java");
        Files.createDirectories(controller.getParent());
        Files.writeString(controller, "package com.acme.order;\nclass OrderController {}\n");
        Files.createDirectories(paths.spec("order.create").getParent());
        Files.writeString(
                paths.spec("order.create"),
                SpecTestData.CREATE_ORDER
                        .replace("status=draft", "status=approved rev=R2")
                        .replace("source=observed hint.layer", "source=observed priority=P0 hint.layer"));
        git("init", "-q");
        git("config", "user.email", "t@example.com");
        git("config", "user.name", "t");
        git("add", "-A");
        git("commit", "-qm", "init");
        ctx = new EngineContext(root, new TestRunnerFake());
    }

    private CaseLedger ledger() {
        return CaseLedger.read(paths.cases("order.create"), "order.create");
    }

    private void lockS01(String revision) {
        OracleShard.Entry e = new OracleShard.Entry();
        e.scenarioRevision = revision;
        e.testClass = TEST_CLASS;
        e.tokens = List.of("201");
        OracleShard.write(paths.oracleShard("order.create"), Map.of(S01 + "#component-01", e));
    }

    private static PlanItem item() {
        PlanItem i = new PlanItem();
        i.id = S01 + "#component-01";
        i.kind = "test";
        i.ref = S01;
        i.flowId = "order.create";
        i.testClass = TEST_CLASS;
        return i;
    }

    @Test
    void shouldCreateTheLedgerAndIndexWhenTheSpecIsApproved() throws Exception {
        List<String> written = ctx.cases.afterApproval("order.create");

        assertEquals(
                List.of(".gta/specs/order/create.cases.md", ".gta/specs/INDEX.md"),
                written,
                "committed with the approval");
        assertEquals(2, ledger().rows().size());
        assertEquals("P0", ledger().row(S01).orElseThrow().priority);
        assertTrue(Files.readString(paths.casesIndex()).contains("order.create"));
    }

    @Test
    void shouldRecordTheAutomatingTestAndItsResultWhenAnItemIsCommitted() throws Exception {
        ctx.cases.afterApproval("order.create");
        RunResult run = new RunResult();
        TestOutcome c = new TestOutcome();
        c.className = TEST_CLASS;
        c.method = "shouldCreate";
        c.outcome = "passed";
        c.tags = new ArrayList<>(List.of("scenario:" + S01));
        run.cases.add(c);
        Json.writeAtomic(paths.runs().resolve("r1").resolve("run.json"), run);

        ctx.cases.afterItemCommit(item(), "r1", false);

        CaseLedger.Row row = ledger().row(S01).orElseThrow();
        assertEquals(TEST_CLASS + "#shouldCreate", row.automation);
        assertTrue(row.result.startsWith("Pass "), row.result);
        assertEquals(CaseLedger.ACTIVE, row.status);
    }

    @Test
    void shouldRecordAPinnedDefectAsAKnownDefect() {
        ctx.cases.afterApproval("order.create");

        ctx.cases.afterItemCommit(item(), "no-evidence", true);

        CaseLedger.Row row = ledger().row(S01).orElseThrow();
        assertTrue(row.result.startsWith("Known defect "), row.result);
        assertEquals(TEST_CLASS, row.automation, "without run evidence the class is the automation");
    }

    @Test
    void shouldMarkFailingAndUnstableCasesForReviewAtSessionEnd() {
        ctx.cases.afterApproval("order.create");
        TestOutcome failed = new TestOutcome();
        failed.className = TEST_CLASS;
        failed.method = "shouldCreate";
        failed.outcome = "failed";
        failed.tags = new ArrayList<>(List.of("scenario:" + S01));

        Map<String, Object> out = ctx.cases.afterSession(List.of(failed), List.of());

        CaseLedger.Row row = ledger().row(S01).orElseThrow();
        assertTrue(row.result.startsWith("Fail "), row.result);
        assertEquals(CaseLedger.NEEDS_REVIEW, row.status);
        assertFalse(((List<?>) out.get("changes")).isEmpty(), "the report lists the change");

        failed.outcome = "passed";
        ctx.cases.afterSession(List.of(failed), List.of(S01 + "#component-01"));
        assertTrue(
                ledger().row(S01).orElseThrow().result.startsWith("Fail "),
                "unstable under the variant run counts as a failure");
    }

    @Test
    void shouldRebuildLedgersOfApprovedSpecsFromTheCommittedLocks() {
        lockS01("rev-of-the-test");

        Map<String, Object> out = ctx.cases.rebuild();

        assertTrue(String.valueOf(out.get("result")).contains("1 approved spec"), out.toString());
        assertEquals(TEST_CLASS, ledger().row(S01).orElseThrow().automation);
        assertEquals(CaseLedger.NEEDS_REVIEW, ledger().row(S01).orElseThrow().status, "locked on another revision");
    }

    @Test
    void shouldDeleteOnlyTheTestsOfConfirmedRetiredCases() throws Exception {
        Path test = paths.testSource(TEST_CLASS);
        Files.createDirectories(test.getParent());
        Files.writeString(test, """
                package com.acme.order;

                class OrderControllerIT {
                    @Test
                    @Tag("scenario:order.create/s01")
                    void shouldCreate() {}

                    @Test
                    @Tag("scenario:order.create/s02")
                    void shouldStayDeveloperFriendly() {}
                }
                """);
        Path fixture = root.resolve("src/test/resources/com/acme/order/OrderControllerIT/s01.request.json");
        Files.createDirectories(fixture.getParent());
        Files.writeString(fixture, "{}");
        lockS01(com.gta.core.spec.SpecParser.parse(paths.spec("order.create"))
                .scenario(S01)
                .orElseThrow()
                .revision());
        ctx.cases.afterApproval("order.create");
        String text = Files.readString(paths.cases("order.create"))
                .replaceFirst("(\\| order\\.create/s01 .*)\\| Active \\|", "$1| Retired |");
        com.gta.core.util.AtomicFiles.write(paths.cases("order.create"), text);
        git("add", "-A");
        git("commit", "-qm", "tests");

        GtaException notRetired =
                assertThrows(GtaException.class, () -> ctx.cases.confirmRetired(List.of("order.create/s02"), false));
        assertEquals(ErrorCode.USAGE_UNKNOWN_ITEM, notRetired.code());

        ctx.cases.confirmRetired(List.of(), true);

        String after = Files.readString(test);
        assertFalse(after.contains("shouldCreate"), after);
        assertTrue(after.contains("shouldStayDeveloperFriendly"), "other scenarios' tests stay");
        assertFalse(Files.exists(fixture), "its fixtures go too");
        assertTrue(OracleShard.read(paths.oracleShard("order.create")).isEmpty(), "and its lock");
        assertTrue(ledger().row(S01).isEmpty());
        assertTrue(git("diff", "--quiet", "HEAD").ok(), "the deletion is committed");
    }

    @Test
    void shouldMarkCasesWhoseCodeChangedAndReturnTheRegressionScope() throws Exception {
        ctx.cases.afterApproval("order.create");
        git("add", "-A");
        git("commit", "-qm", "ledger");
        String base = git("rev-parse", "HEAD").output().strip();
        Files.writeString(
                root.resolve("src/main/java/com/acme/order/OrderController.java"),
                "package com.acme.order;\nclass OrderController { int changed; }\n");

        Map<String, Object> out = ctx.cases.impact(base);

        assertEquals(
                List.of(S01, "order.create/s02"),
                out.get("needsReview"),
                "both scenarios of the requirement that refers to it");
        assertEquals(List.of(S01, "order.create/s02"), out.get("regressionScope"));
        assertEquals(CaseLedger.NEEDS_REVIEW, ledger().row(S01).orElseThrow().status);
    }

    @Test
    void shouldListRetiredCasesForTheReport() {
        String section = CaseLedgers.reportSection(
                List.of(new CaseLedger.Change(S01, "Active, Not run", "Active, Pass 2026-11-04 a1b2c3d")),
                List.of("order.create/s02"));

        assertTrue(section.contains("`order.create/s01`: Active, Not run → Active, Pass"), section);
        assertTrue(section.contains("./gtaw cases confirm-retired"), section);
        assertEquals("", CaseLedgers.reportSection(List.of(), List.of()));
    }
}
