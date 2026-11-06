package com.gta.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.oracle.Lock;
import com.gta.core.plan.PlanItem;
import com.gta.core.run.TestRunnerFake;
import com.gta.core.spec.AnswerParser;
import com.gta.core.spec.SpecParser;
import com.gta.core.spec.SpecTestData;
import com.gta.core.state.ItemStatus;
import com.gta.core.state.StateStore;
import com.gta.core.state.WorkState;
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

/** Triage verdicts and human decisions on items (plan §5.3, §5.5). */
class TriageTest {
    static final String ITEM = "order.create/s01#component-01";

    @TempDir
    Path root;

    GtaPaths paths;
    StateStore store;
    EngineContext ctx;
    WorkState st;
    WorkState.ItemState s;

    private void git(String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessRunner.run(root, Duration.ofSeconds(30), Map.of(), cmd);
    }

    @BeforeEach
    void triagedItem() throws Exception {
        paths = new GtaPaths(root);
        store = new StateStore(paths);
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Files.createDirectories(paths.spec("order.create").getParent());
        Files.writeString(
                paths.spec("order.create"),
                SpecTestData.CREATE_ORDER.replace("status=draft", "status=approved rev=R2"));
        git("init", "-q");
        git("config", "user.email", "t@example.com");
        git("config", "user.name", "t");
        git("add", "-A");
        git("commit", "-qm", "init");
        store.savePlan(List.of(item("observed")));
        ctx = new EngineContext(root, new TestRunnerFake());
        st = new WorkState();
        st.session = new WorkState.Session();
        st.session.id = "s1";
        s = new WorkState.ItemState();
        s.id = ITEM;
        s.status = ItemStatus.TRIAGING;
        st.items.put(ITEM, s);
    }

    private static PlanItem item(String source) {
        PlanItem i = new PlanItem();
        i.id = ITEM;
        i.kind = "test";
        i.ref = "order.create/s01";
        i.flowId = "order.create";
        i.title = "valid order";
        i.testClass = "com.acme.order.OrderControllerIT";
        i.oracle.source = source;
        i.oracle.then = new ArrayList<>(List.of("201", "50.00"));
        return i;
    }

    private void failedWith(String message) {
        s.lastError = ItemWorkflow.lastError(s, ErrorCode.TEST_ASSERTION_FAILED, message);
    }

    // ------------------------------------------------------------------ triage verdicts

    @Test
    void shouldSendATestDefectBackToTheAuthorWithTheOracleLocked() {
        Map<String, Object> out = ctx.triage.applyTriage(st, item("observed"), s, "test-defect", "wrong JSON path");

        assertEquals(ItemStatus.REPAIRING, s.status);
        assertTrue(s.reason.startsWith("test defect: wrong JSON path"), s.reason);
        assertTrue(String.valueOf(out.get("result")).contains("oracle stays locked"));
    }

    @Test
    void shouldAllowOnlyWaitingChangesForAFlakyTest() {
        ctx.triage.applyTriage(st, item("observed"), s, "flaky", "awaits a fixed sleep");

        assertTrue(s.reason.startsWith("flaky: only waiting/synchronisation may change."), s.reason);
    }

    @Test
    void shouldGoToAHumanWhenRepairAttemptsAreUsedUp() {
        s.attempts = 1;

        ctx.triage.applyTriage(st, item("observed"), s, "test-defect", "still wrong");

        assertEquals(ItemStatus.NEEDS_HUMAN, s.status, "maxAttemptsPerItem is 2");
    }

    @Test
    void shouldRefuseAProductDefectOnAnExpectationNoHumanBacked() {
        GtaException e = assertThrows(
                GtaException.class,
                () -> ctx.triage.applyTriage(st, item("observed"), s, "product-defect", "looks wrong"));

        assertEquals(ErrorCode.USAGE_INVALID_ARGUMENT, e.code(), "an observed value cannot prove a defect");
    }

    @Test
    void shouldAskAHumanAboutADefectOnAConfirmedExpectation() {
        st.consecutiveFailures = 2;

        ctx.triage.applyTriage(st, item("confirmed"), s, "product-defect", "discount uses >");

        assertEquals(ItemStatus.NEEDS_HUMAN, s.status);
        assertTrue(s.reason.startsWith("defect:"));
        assertEquals(0, st.consecutiveFailures, "a finding is not an authoring failure");
    }

    @Test
    void shouldAskAHumanForOtherVerdicts() {
        ctx.triage.applyTriage(st, item("observed"), s, "environment", "H2 lacks MERGE");

        assertEquals(ItemStatus.NEEDS_HUMAN, s.status);
        assertEquals("environment: H2 lacks MERGE", s.reason);
    }

    @Test
    void shouldAskAHumanAboutASpecErrorOnAHumanBackedExpectation() {
        ctx.triage.applyTriage(st, item("documented"), s, "spec-error", "docs are outdated");

        assertEquals(ItemStatus.NEEDS_HUMAN, s.status);
    }

    @Test
    void shouldCorrectAnObservedExpectationFromTheActualValue() throws Exception {
        failedWith("expected: <50.00> but was: <55.00>");
        s.lock = new Lock();
        s.lock.tokens = new ArrayList<>(List.of("201", "50.00"));
        s.lock.statements = new ArrayList<>(List.of("isEqualTo(201)", "value(50.00)"));

        Map<String, Object> out = ctx.triage.applyTriage(st, item("observed"), s, "spec-error", "tax is included");

        String spec = Files.readString(paths.spec("order.create"));
        assertTrue(spec.contains("the total is `55.00`"), spec);
        assertTrue(spec.contains("rev=R3"), "a corrected spec is a new revision");
        assertEquals(List.of("201", "55.00"), store.planItem(ITEM).oracle.then);
        assertEquals(List.of("201", "55.00"), s.lock.tokens);
        assertEquals(List.of("isEqualTo(201)"), s.lock.statements, "the old assertion is no longer locked");
        assertEquals(
                com.gta.core.spec.SpecParser.parse(paths.spec("order.create"))
                        .scenario("order.create/s01")
                        .orElseThrow()
                        .revision(),
                store.planItem(ITEM).scenarioRevision,
                "the plan follows the corrected scenario, so a plan sync does not see it as changed");
        assertEquals(ItemStatus.REPAIRING, s.status);
        assertTrue(st.pendingReview.get(0).contains("`50.00` → `55.00`"), "listed for re-confirmation");
        assertTrue(String.valueOf(out.get("result")).contains("order.create@R3"), out.toString());
    }

    @Test
    void shouldReadAssertJMessagesToo() throws Exception {
        failedWith("expected: \"50.00\"\n but was: \"55.00\"\n");

        ctx.triage.applyTriage(st, item("observed"), s, "spec-error", "tax is included");

        assertTrue(Files.readString(paths.spec("order.create")).contains("`55.00`"));
    }

    @Test
    void shouldAskAHumanWhenTheActualValueCannotBeRead() {
        failedWith("NullPointerException");

        ctx.triage.applyTriage(st, item("observed"), s, "spec-error", "?");

        assertEquals(ItemStatus.NEEDS_HUMAN, s.status);
        assertTrue(s.reason.contains("could not be read"), s.reason);
    }

    // ------------------------------------------------------------------ human decisions

    private Map<String, Object> decide(String type, String letter) {
        s.status = ItemStatus.NEEDS_HUMAN;
        WorkState.Pending p = new WorkState.Pending();
        p.type = type;
        p.itemId = ITEM;
        p.recommended = "A";
        st.pending = p;
        return ctx.triage.applyDecision(st, p, AnswerParser.parse(letter), letter);
    }

    @Test
    void shouldTakeTheRecommendedOptionOnOk() {
        decide("defect", "OK");

        assertEquals("known-defect", s.intentOverride, "OK takes A, the recommended option");
        assertThrows(GtaException.class, () -> decide("defect", "OK, 2B"), "a decision takes one letter or OK");
    }

    @Test
    void shouldPinAConfirmedDefect() {
        decide("defect", "A");

        assertEquals("known-defect", s.intentOverride);
        assertEquals(ItemStatus.PENDING, s.status);
        assertTrue(Files.exists(paths.escalations().resolve("order.create__s01__component-01.md")));
        assertEquals(null, st.pending);
    }

    @Test
    void shouldSendTheSpecBackWhenTheHumanSaysTheExpectationIsWrong() throws Exception {
        decide("defect", "B");

        assertEquals(ItemStatus.CANCELLED, s.status);
        assertTrue(st.specWork.get("order.create").needsRevision);
        assertTrue(SpecParser.parse(paths.spec("order.create")).status().equals("in-review"));
    }

    @Test
    void shouldRetryOrCancelAnEscalation() {
        s.attempts = 2;
        decide("defect", "C");
        assertEquals(ItemStatus.PENDING, s.status);
        assertEquals(0, s.attempts);

        decide("escalation", "A");
        assertEquals(ItemStatus.PENDING, s.status);

        decide("escalation", "B");
        assertEquals(ItemStatus.CANCELLED, s.status);
        assertEquals(3, st.newConfirmations.size(), "every human decision is recorded with its words");
    }

    @Test
    void shouldRefuseALetterThatIsNotOffered() {
        assertThrows(GtaException.class, () -> decide("escalation", "C"));
        assertThrows(GtaException.class, () -> decide("naming-conflict", "D"));
        assertThrows(GtaException.class, () -> decide("escalation", "R1: 1A"));
    }

    @Test
    void shouldCheckANamingConflictAgainWhenTheDeveloperMovedTheirFile() {
        Map<String, Object> out = decide("naming-conflict", "C");

        assertEquals(false, s.sharesDeveloperFile);
        assertTrue(String.valueOf(out.get("result")).contains("checked again"));
    }

    // ------------------------------------------------------------------ catching tests (plan §6, P3)

    private static PlanItem catching() {
        PlanItem i = item("observed");
        i.intent = "catching";
        return i;
    }

    @Test
    void shouldRefuseACatchThatFailsOnTheBaseToo() {
        s.onBase = "fails";

        GtaException e = assertThrows(
                GtaException.class, () -> ctx.triage.applyTriage(st, catching(), s, "product-defect", "looks broken"));

        assertEquals(ErrorCode.USAGE_INVALID_ARGUMENT, e.code(), "the change did not cause it: " + e.getMessage());
    }

    @Test
    void shouldSendACatchCandidateToAHumanWhateverItsOracleSource() {
        s.onBase = "passes";

        ctx.triage.applyTriage(st, catching(), s, "product-defect", "the change dropped the null check");

        assertEquals(ItemStatus.NEEDS_HUMAN, s.status, "an observed catching scenario can still be a catch");
        assertTrue(s.reason.startsWith("defect:"), "the human gets the defect decision: " + s.reason);
    }

    @Test
    void shouldLetAHumanJudgeASpecErrorOnACatchingTestInsteadOfCorrectingIt() throws Exception {
        String before = Files.readString(paths.spec("order.create"));
        failedWith("expected: <201> but was: <500>");

        ctx.triage.applyTriage(st, catching(), s, "spec-error", "the new code answers 500");

        assertEquals(ItemStatus.NEEDS_HUMAN, s.status);
        assertEquals(before, Files.readString(paths.spec("order.create")), "the THEN is not auto-corrected");
    }
}
