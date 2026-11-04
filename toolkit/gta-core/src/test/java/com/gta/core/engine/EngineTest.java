package com.gta.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.change.ChangeChecker;
import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.oracle.Lock;
import com.gta.core.oracle.OracleShard;
import com.gta.core.plan.PlanItem;
import com.gta.core.run.RunResult;
import com.gta.core.run.TestOutcome;
import com.gta.core.run.TestRunnerFake;
import com.gta.core.spec.SpecParser;
import com.gta.core.spec.SpecTestData;
import com.gta.core.state.ItemStatus;
import com.gta.core.state.StateStore;
import com.gta.core.state.WorkState;
import com.gta.core.util.ProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The item and spec state machines, driven with a fake test runner (no Maven). */
class EngineTest {
    static final String ITEM = "order.create/s01#component-01";
    static final String TOKEN = "s20261104090000-test:0007";

    static RunResult timedOut() {
        RunResult r = new RunResult();
        r.timedOut = true;
        return r;
    }

    static RunResult outcome(String outcome) {
        RunResult r = new RunResult();
        TestOutcome c = new TestOutcome();
        c.className = "com.acme.OrderCreateIT";
        c.method = "shouldCreate";
        c.outcome = outcome;
        c.tags = List.of("gta", "scenario:order.create/s01");
        r.cases.add(c);
        return r;
    }

    @TempDir
    Path root;

    GtaPaths paths;
    StateStore store;
    TestRunnerFake runner;

    private void git(String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessRunner.run(root, Duration.ofSeconds(30), Map.of(), cmd);
    }

    @BeforeEach
    void repository() throws Exception {
        paths = new GtaPaths(root);
        store = new StateStore(paths);
        runner = new TestRunnerFake();
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Files.writeString(root.resolve(".gitignore"), ".gta/work/\n");
        Files.createDirectories(paths.mainJava());
        Files.writeString(paths.mainJava().resolve("App.java"), "class App {}\n");
        git("init", "-q");
        git("config", "user.email", "t@example.com");
        git("config", "user.name", "t");
        git("add", "-A");
        git("commit", "-qm", "init");

        PlanItem item = new PlanItem();
        item.id = ITEM;
        item.kind = "test";
        item.ref = "order.create/s01";
        item.flowId = "order.create";
        item.title = "valid order";
        item.layer = "component";
        item.testClass = "com.acme.OrderCreateIT";
        item.ownsFiles.add(GtaPaths.testSourceRel(item.testClass));
        item.oracle.source = "observed";
        store.savePlan(List.of(item));
    }

    private WorkState state(ItemStatus status) {
        WorkState st = new WorkState();
        st.session = new WorkState.Session();
        st.session.id = "s20261104090000-test";
        st.session.lastSeenAt = Instant.now().toString();
        WorkState.ItemState s = new WorkState.ItemState();
        s.id = ITEM;
        s.status = status;
        s.token = TOKEN;
        s.baseline = new ChangeChecker(paths).capture();
        st.items.put(ITEM, s);
        return st;
    }

    private WorkState.ItemState item() {
        return store.load().items.get(ITEM);
    }

    private Engine engine() {
        return new Engine(root, runner);
    }

    // ------------------------------------------------------------------ review

    @Test
    void shouldSendARejectedOracleBackToTheAuthor() {
        store.save(state(ItemStatus.REVIEWING));

        engine().submitReview(ITEM, TOKEN, "reject", "asserts the wrong field");

        assertEquals(ItemStatus.REPAIRING, item().status);
        assertTrue(item().lastError.message.contains("asserts the wrong field"));
    }

    @Test
    void shouldRefuseAReviewVerdictWithoutTheCurrentToken() {
        store.save(state(ItemStatus.REVIEWING));

        GtaException e =
                assertThrows(GtaException.class, () -> engine().submitReview(ITEM, "stale-token", "accept", "late"));

        assertEquals(ErrorCode.STATE_STALE_SESSION, e.code());
        assertEquals(ItemStatus.REVIEWING, item().status);
    }

    @Test
    void shouldRejectAnUnknownOracleVerdictWithoutChangingState() {
        store.save(state(ItemStatus.REVIEWING));

        assertThrows(GtaException.class, () -> engine().submitReview(ITEM, TOKEN, "maybe", "unsure"));

        assertEquals(ItemStatus.REVIEWING, item().status);
    }

    // ------------------------------------------------------------------ submit

    @Test
    void shouldCountATimedOutRunAndEscalateWhenItRepeats() {
        store.save(state(ItemStatus.AUTHORING));
        runner.then(timedOut());

        Map<String, Object> first = engine().submitItem(ITEM, TOKEN);
        assertEquals(ErrorCode.TEST_TIMEOUT.name(), first.get("code"), first.toString());
        assertEquals(ItemStatus.REPAIRING, item().status);
        assertEquals(1, item().attempts);

        engine().submitItem(ITEM, TOKEN);
        assertEquals(ItemStatus.NEEDS_HUMAN, item().status, "the same timeout twice goes to a human");
        assertTrue(Files.exists(paths.escalations().resolve("order.create__s01__component-01.md")));
    }

    @Test
    void shouldRefuseAKnownDefectPinNoHumanConfirmed() {
        store.save(state(ItemStatus.AUTHORING));
        runner.then(outcome("known-defect"));

        Map<String, Object> out = engine().submitItem(ITEM, TOKEN);

        assertEquals(ErrorCode.POLICY_UNCONFIRMED_KNOWN_DEFECT.name(), out.get("code"), out.toString());
        assertEquals(ItemStatus.REPAIRING, item().status);
    }

    @Test
    void shouldSendCommitsMadeOutsideGtawToAHuman() throws Exception {
        store.save(state(ItemStatus.AUTHORING));
        runner.then(outcome("passed"));
        Files.writeString(paths.mainJava().resolve("App.java"), "class App { int bent; }\n");
        git("commit", "-qam", "fix\n\nGta-Item: forged trailer");

        Map<String, Object> out = engine().submitItem(ITEM, TOKEN);

        assertEquals(ItemStatus.NEEDS_HUMAN, item().status, out.toString());
        assertTrue(item().reason.contains("commits made outside gtaw"), item().reason);
        assertEquals(0, runner.runs(), "no test run on a tampered tree");
    }

    @Test
    void shouldLeaveNoShardBehindWhenTheCommitFails() throws Exception {
        WorkState st = state(ItemStatus.REVIEWING);
        st.items.get(ITEM).lock = new Lock();
        st.items.get(ITEM).lock.tokens.add("201");
        st.items.get(ITEM).evidenceRunId = "r1";
        store.save(st);
        Path test = root.resolve(GtaPaths.testSourceRel("com.acme.OrderCreateIT"));
        Files.createDirectories(test.getParent());
        Files.writeString(test, "class OrderCreateIT {}\n");
        git("config", "commit.gpgsign", "true");
        git("config", "gpg.format", "openpgp");
        git("config", "gpg.program", "false-gpg-that-does-not-exist");

        GtaException e = assertThrows(
                GtaException.class, () -> engine().submitReview(ITEM, TOKEN, "accept", "matches the spec"));

        assertEquals(ErrorCode.ENV_GIT_FAILED, e.code());
        assertFalse(
                Files.exists(paths.oracleShard("order.create")),
                "gtaw's own shard write is undone with the failed commit");
        assertEquals(ItemStatus.REVIEWING, item().status, "the state is unchanged, so the verdict can be given again");
        assertTrue(
                ProcessRunner.run(root, ProcessRunner.cmd("git", "diff", "--cached", "--quiet"))
                        .ok(),
                "nothing left staged");
    }

    @Test
    void shouldDropAPendingDecisionWhenItsScenarioChanges() {
        WorkState st = state(ItemStatus.NEEDS_HUMAN);
        st.pending = new WorkState.Pending();
        st.pending.type = "defect";
        st.pending.itemId = ITEM;
        PlanItem old = store.planItem(ITEM);
        old.scenarioRevision = "before-the-spec-changed";
        store.savePlan(List.of(old));
        EngineContext ctx = new EngineContext(root, runner);

        ctx.planSync.syncPlan(
                st, SpecParser.parseText(SpecTestData.CREATE_ORDER), new com.gta.core.profile.ServiceProfile());

        assertEquals(null, st.pending, "the human decision was about the old scenario");
        assertEquals(ItemStatus.PENDING, st.items.get(ITEM).status, "the item starts again from the new scenario");
    }

    // ------------------------------------------------------------------ human decisions

    @Test
    void shouldGiveARetriedItemAFreshCheckBudget() {
        WorkState st = state(ItemStatus.NEEDS_HUMAN);
        st.items.get(ITEM).checks = 8;
        st.items.get(ITEM).attempts = 2;
        st.pending = new WorkState.Pending();
        st.pending.type = "escalation";
        st.pending.itemId = ITEM;
        store.save(st);

        engine().answer("A");

        assertEquals(ItemStatus.PENDING, item().status);
        assertEquals(0, item().checks);
        assertEquals(0, item().attempts);
    }

    private void specInReview() {
        EngineContext ctx = new EngineContext(root, runner);
        ctx.specs.writeSpec(
                paths.spec("order.create"),
                SpecTestData.CREATE_ORDER
                        .replace("status=draft", "status=in-review rev=R2")
                        .replace("source=assumed hint.layer=component", "source=observed hint.layer=component")
                        .replaceAll("(?s)## Open questions.*## Findings", "## Open questions\n\n## Findings"));
        WorkState st = state(ItemStatus.PENDING);
        st.pending = new WorkState.Pending();
        st.pending.type = "approve";
        st.pending.flowId = "order.create";
        st.pending.rev = "R2";
        store.save(st);
    }

    @Test
    void shouldApproveExactlyWhatWasShown() {
        specInReview();

        engine().answer("批准 order.create@R2");

        assertEquals("approved", SpecParser.parse(paths.spec("order.create")).status());
    }

    @Test
    void shouldNotApproveASpecEditedAfterItWasShown() throws Exception {
        specInReview();
        Path spec = paths.spec("order.create");
        Files.writeString(spec, Files.readString(spec).replace("the total is `50.00`", "the total is `0.00`"));

        Map<String, Object> out = engine().answer("approve order.create@R2");

        assertTrue(String.valueOf(out.get("result")).startsWith("not approved"), out.toString());
        assertEquals("in-review", SpecParser.parse(spec).status());
        assertTrue(store.load().specWork.get("order.create").needsRevision);
    }

    @Test
    void shouldTreatANegatedApprovalAsAChangeRequest() {
        specInReview();

        Map<String, Object> out = engine().answer("不批准 order.create@R2，s02 还要改");

        assertTrue(String.valueOf(out.get("result")).startsWith("not approved"), out.toString());
        assertEquals("in-review", SpecParser.parse(paths.spec("order.create")).status());
        assertTrue(
                store.load().specWork.get("order.create").answersToApply.get(0).contains("s02 还要改"));
    }

    // ------------------------------------------------------------------ sessions

    @Test
    void shouldKeepItemsWaitingForTriageWhenANewSessionStarts() {
        WorkState st = state(ItemStatus.TRIAGING);
        st.sessionEnded = true;
        EngineContext ctx = new EngineContext(root, runner);

        ctx.sessions.touchSession(st, false);

        assertEquals(ItemStatus.TRIAGING, st.items.get(ITEM).status);
        assertFalse(st.sessionEnded);
    }

    @Test
    void shouldResetOnlyEditingItemsOnTakeover() {
        WorkState st = state(ItemStatus.AUTHORING);
        WorkState.ItemState review = new WorkState.ItemState();
        review.id = "order.create/s02#component-01";
        review.status = ItemStatus.REVIEWING;
        st.items.put(review.id, review);
        EngineContext ctx = new EngineContext(root, runner);

        ctx.sessions.touchSession(st, true);

        assertEquals(ItemStatus.PENDING, st.items.get(ITEM).status);
        assertEquals(ItemStatus.REVIEWING, st.items.get(review.id).status);
    }

    // ------------------------------------------------------------------ relock

    @Test
    void shouldRelockOnTheSpecsExpectedValues() throws Exception {
        Files.createDirectories(paths.spec("order.create").getParent());
        Files.writeString(
                paths.spec("order.create"),
                SpecTestData.CREATE_ORDER.replace("status=draft", "status=approved rev=R2"));
        Files.createDirectories(paths.testSource("com.acme.OrderCreateIT").getParent());
        Files.writeString(
                paths.testSource("com.acme.OrderCreateIT"),
                """
                class OrderCreateIT {
                    @Test
                    @Tag("scenario:order.create/s01")
                    void shouldCreate() {
                        assertThat(status).isEqualTo(201);
                        assertThat(total).isEqualByComparingTo("50.00");
                    }
                }
                """);
        OracleShard.Entry old = new OracleShard.Entry();
        old.testClass = "com.acme.OrderCreateIT";
        old.tokens = List.of("201", "49.00");
        old.fingerprint = "000000000000";
        OracleShard.write(paths.oracleShard("order.create"), Map.of(ITEM, old));

        engine().relock(ITEM, "assertion style changed", "alice@corp");

        OracleShard.Entry now =
                OracleShard.read(paths.oracleShard("order.create")).get(ITEM);
        assertEquals(List.of("201", "50.00"), now.tokens, "tokens come from the spec, not the old lock");
        assertEquals(
                SpecParser.parse(paths.spec("order.create"))
                        .scenario("order.create/s01")
                        .orElseThrow()
                        .revision(),
                now.scenarioRevision);
        assertTrue(now.relockedAt != null);
    }

    @Test
    void shouldRejectARelockOfANonScenarioId() {
        GtaException e = assertThrows(GtaException.class, () -> engine().relock("order#component-01", "x", "alice"));

        assertEquals(ErrorCode.USAGE_UNKNOWN_ITEM, e.code());
    }

    @Test
    void shouldRefuseToGuessTheCapabilityOfAFlowThatIsNotInTheCode() {
        GtaException e =
                assertThrows(GtaException.class, () -> engine().next(null, "billing.recalculate", null, false));

        assertEquals(ErrorCode.USAGE_UNKNOWN_FLOW, e.code(), "a guessed capability would brief the analyst wrongly");
        assertEquals(null, store.load().activeFlow, "nothing is saved");
    }
}
