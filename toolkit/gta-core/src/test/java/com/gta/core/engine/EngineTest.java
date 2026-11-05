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
import org.junit.jupiter.api.Nested;
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

    private ProcessRunner.Result git(String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return ProcessRunner.run(root, Duration.ofSeconds(30), Map.of(), cmd);
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
        item.ownsFiles.add(new GtaPaths(root).testSourceRel(item.testClass));
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
        assertTrue(
                com.gta.core.state.Lessons.recent(paths, 10).stream().anyMatch(l -> l.startsWith("- [reviewer] ")),
                "the reject is a lesson for the next author");
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
        Path test = root.resolve(new GtaPaths(root).testSourceRel("com.acme.OrderCreateIT"));
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
        Files.writeString(paths.testSource("com.acme.OrderCreateIT"), """
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

    private Map<String, Object> nextOnApproved(String capability) throws Exception {
        Files.createDirectories(paths.spec("order.create").getParent());
        Files.writeString(
                paths.spec("order.create"),
                SpecTestData.CREATE_ORDER
                        .replace("capability=rest-api", "capability=" + capability)
                        .replace("status=draft", "status=approved rev=R1"));
        return engine().next(null, "order.create", null, false);
    }

    @Test
    void shouldWriteTestsForTheCapabilitiesOfP1AndP2() throws Exception {
        for (String capability : List.of("scheduler", "messaging", "spring-batch", "db-migration")) {
            Map<String, Object> out = nextOnApproved(capability);

            assertFalse(String.valueOf(out).contains("explain-only"), capability + ": " + out);
            assertEquals("author", out.get("node"), capability + " is dispatched to the test author: " + out);
            engine().abort(null);
        }
    }

    @Test
    void shouldKeepLaterCapabilitiesExplainOnly() throws Exception {
        Map<String, Object> out = nextOnApproved("grpc-api");

        assertTrue(String.valueOf(out).contains("explain-only"), out.toString());
    }

    @Test
    void shouldRefuseToGuessTheCapabilityOfAFlowThatIsNotInTheCode() {
        GtaException e =
                assertThrows(GtaException.class, () -> engine().next(null, "billing.recalculate", null, false));

        assertEquals(ErrorCode.USAGE_UNKNOWN_FLOW, e.code(), "a guessed capability would brief the analyst wrongly");
        assertEquals(null, store.load().activeFlow, "nothing is saved");
    }

    private ItemWorkflow.Dispatch dispatchWithDeveloperTest(WorkState st) throws Exception {
        Path test = root.resolve(new GtaPaths(root).testSourceRel("com.acme.OrderCreateIT"));
        Files.createDirectories(test.getParent());
        Files.writeString(test, "class OrderCreateIT { @org.junit.jupiter.api.Test void mine() {} }\n");
        EngineContext ctx = new EngineContext(root, runner);
        return ctx.items.dispatch(
                st,
                SpecParser.parseText(SpecTestData.CREATE_ORDER),
                new com.gta.core.profile.ServiceProfile(),
                store.loadPlan());
    }

    @Test
    void shouldAskBeforeWritingIntoADevelopersTestClass() throws Exception {
        WorkState st = state(ItemStatus.PENDING);

        ItemWorkflow.Dispatch d = dispatchWithDeveloperTest(st);

        assertEquals("naming-conflict", st.pending.type, "gta never decides for the developer");
        assertEquals("answer", d.node().get("node"));
        assertTrue(st.pending.prompt.contains("src/test/java/com/acme/OrderCreateIT.java"), st.pending.prompt);
        assertEquals(ItemStatus.PENDING, st.items.get(ITEM).status, "nothing was written");
    }

    @Test
    void shouldShareTheDevelopersTestClassOnlyWhenTheHumanSaysSo() throws Exception {
        WorkState st = state(ItemStatus.PENDING);
        dispatchWithDeveloperTest(st);
        store.save(st);

        engine().answer("A");

        assertTrue(item().sharesDeveloperFile);
        WorkState again = store.load();
        ItemWorkflow.Dispatch d = new EngineContext(root, runner)
                .items.dispatch(
                        again,
                        SpecParser.parseText(SpecTestData.CREATE_ORDER),
                        new com.gta.core.profile.ServiceProfile(),
                        store.loadPlan());
        assertEquals("author", d.node().get("node"), "the item is briefed after the human agreed");
    }

    @Test
    void shouldCancelTheItemWhenTheHumanSkipsIt() throws Exception {
        WorkState st = state(ItemStatus.PENDING);
        dispatchWithDeveloperTest(st);
        store.save(st);

        engine().answer("B");

        assertEquals(ItemStatus.CANCELLED, item().status);
    }

    /**
     * The whole loop of plan §5 in-process, as the scripted e2e drives it through the CLI: spec, questions, approval,
     * foundation, oracle lock, scope check, oracle review, triage of a confirmed defect, pinning, reject and session end.
     * Test runs are answered by scenario, so every gtaw decision is exercised without Maven.
     */
    @Nested
    class FullLoop {
        static final Path REPO = Path.of("../..").toAbsolutePath().normalize();
        static final Path SCRIPTED = REPO.resolve("evals/e2e/scripted-output");
        static final String FLOW = "order.create-order";
        static final String TEST = "src/test/java/com/example/order/api/OrderControllerIT.java";

        /** Scenario id -> outcome its test run reports ("passed", "failed" or "known-defect"). */
        final Map<String, String> outcomes = new java.util.HashMap<>();

        final com.gta.core.run.TestRunner byScenario = (runId, itemId, options, prod, tests) -> {
            RunResult r = new RunResult();
            r.runId = runId;
            List<String> scenarios = new ArrayList<>();
            String tag = options.scenarioTag();
            if (tag != null && tag.startsWith("scenario:")) {
                scenarios.add(tag.substring("scenario:".length()));
            } else if (options.testClass() != null && options.testClass().endsWith("ApplicationIT")) {
                TestOutcome seed = new TestOutcome();
                seed.className = options.testClass();
                seed.method = "shouldStartContextWhenApplicationBoots";
                seed.outcome = "passed";
                seed.tags = new ArrayList<>(List.of("gta", "scenario:order"));
                r.cases.add(seed);
                return r;
            } else {
                for (int n = 1; n <= 4; n++) {
                    scenarios.add(FLOW + "/s0" + n); // every gta test (gate, layer runs)
                }
            }
            for (String sc : scenarios) {
                TestOutcome c = new TestOutcome();
                c.className = "com.example.order.api.OrderControllerIT";
                c.method = "should" + sc.substring(sc.indexOf('/') + 1);
                c.outcome = outcomes.getOrDefault(sc, "passed");
                c.tags = new ArrayList<>(List.of("gta", "scenario:" + sc));
                if ("failed".equals(c.outcome)) {
                    c.failureType = "java.lang.AssertionError";
                    c.message = "JSON path \"$.discount\" expected:<10.0> but was:<0.0>";
                    c.code = ErrorCode.TEST_ASSERTION_FAILED.name();
                    c.sutReached = true;
                }
                r.cases.add(c);
            }
            return r;
        };

        private Engine loop() {
            return new Engine(root, byScenario);
        }

        @BeforeEach
        void orderService() throws Exception {
            try (var files = Files.walk(REPO.resolve("evals/fixtures/order-service"))) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    Path rel = REPO.resolve("evals/fixtures/order-service").relativize(f);
                    if (rel.startsWith("target")) {
                        continue;
                    }
                    Path to = root.resolve(rel);
                    Files.createDirectories(to.getParent());
                    Files.copy(f, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.deleteIfExists(paths.mainJava().resolve("App.java"));
            Files.deleteIfExists(paths.plan());
            Files.writeString(root.resolve(".gitignore"), "target/\n.gta/work/\n");
            git("add", "-A");
            git("commit", "-qm", "order-service fixture");
            git("branch", "-M", "main");
            git("checkout", "-q", "-b", "feature/gta-tests");
        }

        private void addMethod(String fragment) throws Exception {
            Path test = root.resolve(TEST);
            String text = Files.readString(test);
            int end = text.lastIndexOf('}');
            Files.writeString(test, text.substring(0, end) + Files.readString(SCRIPTED.resolve(fragment)) + "}\n");
        }

        private String token(Map<String, Object> node) {
            return String.valueOf(node.get("token"));
        }

        /** next until the author node of a scenario; a session that ends on its budget is followed by the next one. */
        private Map<String, Object> author(String scenario) {
            Map<String, Object> out = loop().next(null, null, null, false);
            if ("done".equals(out.get("node"))) {
                out = loop().next(null, null, null, false);
            }
            assertEquals(FLOW + "/" + scenario + "#component-01", out.get("itemId"), out.toString());
            return out;
        }

        private void reviewAccept(String scenario) {
            Map<String, Object> review = loop().next(null, null, null, false);
            assertEquals("oracle-review", review.get("kind"), review.toString());
            Map<String, Object> out = loop().submitReview(
                            FLOW + "/" + scenario + "#component-01",
                            token(review),
                            "accept",
                            "asserts the THEN values");
            assertTrue(String.valueOf(out.get("result")).startsWith("passed"), out.toString());
        }

        @Test
        void shouldTakeAFlowFromCodeToCommittedTestsAndAReport() throws Exception {
            session();

            // status, report and abort as a human uses them
            Map<String, Object> status = loop().status();
            assertTrue(status.toString().contains("defect-found"), status.toString());
            assertTrue(loop().report("pr-comment").contains("behaviour & test report"));
            assertTrue(loop().cases(false).toString().contains(FLOW));
            loop().abort(FLOW);
            assertEquals(null, store.load().activeFlow);
        }

        @Test
        void shouldServeTheHumanCommandsAfterASession() throws Exception {
            session();
            Engine e = loop();

            assertEquals(
                    3,
                    ((Number) e.verify(false).get("checked")).intValue(),
                    "every passed item; s03 is a pinned defect");
            assertEquals("concerns", e.gate(null, List.of()).decision);
            assertEquals(1, e.runTests(true, null).cases.size(), "the smoke run is the seed test");
            assertEquals(4, e.runTests(false, "component").cases.size());
            assertEquals(
                    ErrorCode.USAGE_NOT_AVAILABLE_IN_PHASE,
                    assertThrows(GtaException.class, () -> e.runTests(false, "system"))
                            .code());
            assertTrue(e.cases(true).toString().contains("1 approved spec"));
            assertTrue(((List<?>) e.impact(null).get("needsReview")).isEmpty(), "nothing changed since the base");

            // relock after an intended change of an expected value in spec and test
            String s01 = FLOW + "/s01#component-01";
            assertEquals(
                    ErrorCode.USAGE_UNKNOWN_ITEM,
                    assertThrows(GtaException.class, () -> e.relock("order#foundation-01", "x", "me"))
                            .code());
            assertEquals(
                    ErrorCode.USAGE_UNKNOWN_ITEM,
                    assertThrows(GtaException.class, () -> e.relock(FLOW + "/s09#component-01", "x", "me"))
                            .code());
            assertTrue(String.valueOf(
                            e.relock(s01, "same values, renamed test", "me").get("result"))
                    .startsWith("relocked"));
            Path test = root.resolve(TEST);
            Files.writeString(test, Files.readString(test).replace("value(50.00)", "value(49.00)"));
            assertEquals(
                    ErrorCode.POLICY_ORACLE_MISMATCH,
                    assertThrows(GtaException.class, () -> e.relock(s01, "values changed", "me"))
                            .code(),
                    "the test must assert the spec's values");

            // a new conversation: choose a flow, target it by file
            e.abort(FLOW);
            Map<String, Object> choose = e.next(null, null, null, false);
            assertTrue(String.valueOf(choose.get("message")).startsWith("No flow selected"), choose.toString());
            assertTrue(choose.get("candidates").toString().contains(FLOW));
            assertEquals(
                    ErrorCode.USAGE_UNKNOWN_FLOW,
                    assertThrows(GtaException.class, () -> e.next(null, "NoSuchController.java", null, false))
                            .code());

            // production code the spec refers to changed: the approved spec turns stale and goes back to the analyst
            Path pricing = root.resolve("src/main/java/com/example/order/service/PricingService.java");
            Files.writeString(
                    pricing,
                    Files.readString(pricing).replace("compareTo(threshold) > 0", "compareTo(threshold) >= 0"));
            git("commit", "-qam", "fix threshold discount");
            Map<String, Object> stale = e.next(null, FLOW, null, false);
            assertEquals("analyze", stale.get("node"), stale.toString());
            assertEquals("stale", SpecParser.parse(paths.spec(FLOW)).status());

            // abort everything: state goes, evidence and specs stay
            assertTrue(String.valueOf(e.abort(null).get("result")).startsWith("working state discarded"));
            assertTrue(Files.exists(paths.spec(FLOW)));
            assertEquals(null, store.load().session);
        }

        private void session() throws Exception {
            // analyze: spec R1 with one high-impact question
            Map<String, Object> out = loop().next("flow", FLOW, null, false);
            assertEquals("analyze", out.get("node"), out.toString());
            Files.createDirectories(paths.spec(FLOW).getParent());
            Files.copy(SCRIPTED.resolve("create-order.spec.md"), paths.spec(FLOW));
            assertEquals("R1", loop().submitSpec(FLOW, token(out)).get("rev"));
            assertEquals("answer", loop().next(null, null, null, false).get("type"));

            // the human answers: the threshold discount is a bug
            loop().answer("R1: 1B");
            assertTrue(Files.readString(paths.spec(FLOW)).contains("id=order.create-order/s03 source=confirmed"));
            out = loop().next(null, null, null, false);
            assertEquals("analyze", out.get("node"), "revision node");
            String spec = Files.readString(paths.spec(FLOW));
            Files.writeString(
                    paths.spec(FLOW),
                    spec.replace("discount `0.00` and total `100.00`", "discount `10.00` and total `90.00`"));
            assertEquals("R2", loop().submitSpec(FLOW, token(out)).get("rev"));

            // approval commits the spec and creates the case ledger
            out = loop().next(null, null, null, false);
            assertEquals("approve", out.get("type"), out.toString());
            loop().answer("approve " + FLOW + "@R2");
            assertTrue(git("log", "-1", "--format=%s").output().contains(FLOW + " R2 approved"));
            assertTrue(Files.exists(paths.cases(FLOW)), "the ledger is committed with the approval");

            // foundation: the seed test of the application
            out = loop().next(null, null, null, false);
            assertEquals("order#foundation-01", out.get("itemId"), out.toString());
            Path seed = root.resolve("src/test/java/com/example/order/OrderApplicationIT.java");
            Files.createDirectories(seed.getParent());
            Files.copy(SCRIPTED.resolve("OrderApplicationIT.java.txt"), seed);
            out = loop().submitItem("order#foundation-01", token(out));
            assertTrue(String.valueOf(out.get("result")).startsWith("passed"), out.toString());

            // s01: the oracle locks on the THEN values; a production edit is reverted
            out = author("s01");
            String s01 = FLOW + "/s01#component-01";
            Path test = root.resolve(TEST);
            Files.createDirectories(test.getParent());
            Files.writeString(test, Files.readString(SCRIPTED.resolve("OrderControllerIT.head.java.txt")) + "}\n");
            addMethod("s01.method.java.txt");
            Map<String, Object> check = loop().check(s01, token(out));
            assertEquals(List.of("201", "0.00", "50.00", "CREATED"), check.get("oracleLocked"));
            Files.writeString(test, Files.readString(test).replace("value(50.00)", "value(51.00)"));
            assertEquals("modified", loop().check(s01, token(out)).get("oracleLock"), "a locked value was changed");
            Files.writeString(test, Files.readString(test).replace("value(51.00)", "value(50.00)"));
            Path app = root.resolve("src/main/java/com/example/order/OrderApplication.java");
            Files.writeString(app, Files.readString(app) + "// out of scope\n");
            assertEquals(
                    ErrorCode.POLICY_OUT_OF_SCOPE_CHANGE.name(),
                    loop().submitItem(s01, token(out)).get("code"));
            assertTrue(git("diff", "--quiet", "--", "src/main").ok(), "the production edit was reverted");
            loop().submitItem(s01, token(author("s01")));
            reviewAccept("s01");

            // s02
            out = author("s02");
            addMethod("s02.method.java.txt");
            loop().submitItem(FLOW + "/s02#component-01", token(out));
            reviewAccept("s02");

            // s03: the confirmed expectation exposes the seeded defect; a human confirms it and it is pinned
            String s03 = FLOW + "/s03#component-01";
            outcomes.put(FLOW + "/s03", "failed");
            out = author("s03");
            addMethod("s03.method.java.txt");
            assertEquals(
                    ErrorCode.TEST_ASSERTION_FAILED.name(),
                    loop().submitItem(s03, token(out)).get("code"));
            out = loop().next(null, null, null, false);
            assertEquals("triage", out.get("kind"), out.toString());
            loop().submitReview(s03, token(out), "product-defect", "PricingService#discount uses > instead of >=");
            assertEquals("defect", loop().next(null, null, null, false).get("type"));
            loop().answer("A");
            out = loop().next(null, null, null, false);
            assertEquals(s03, out.get("itemId"), "the pin is briefed");
            outcomes.put(FLOW + "/s03", "known-defect");
            out = loop().submitItem(s03, token(out));
            assertTrue(String.valueOf(out.get("result")).startsWith("defect-found"), out.toString());

            // s04: the reviewer rejects the first oracle, the author resubmits
            String s04 = FLOW + "/s04#component-01";
            out = author("s04");
            addMethod("s04.method.java.txt");
            loop().submitItem(s04, token(out));
            out = loop().next(null, null, null, false);
            assertEquals("oracle-review", out.get("kind"));
            out = loop().submitReview(s04, token(out), "reject", "also assert that nothing was stored");
            assertTrue(String.valueOf(out.get("result")).contains("fix it"), out.toString());
            loop().submitItem(s04, token(author("s04")));
            reviewAccept("s04");

            // session end: stability, gate, tracked report, ledger results
            out = loop().next(null, null, null, false);
            assertEquals("done", out.get("node"), out.toString());
            assertEquals("concerns", out.get("gate"), "new tests on a feature branch");
            String report = String.valueOf(out.get("report"));
            assertTrue(report.startsWith(".gta/reports/"), report);
            assertTrue(Files.readString(root.resolve(report)).contains("Product defects found"));
            assertTrue(git("ls-files", "--error-unmatch", report).ok(), "the report is committed");
            String ledger = Files.readString(paths.cases(FLOW));
            assertEquals(
                    4,
                    ledger.lines()
                            .filter(l -> l.startsWith("| " + FLOW + "/s0"))
                            .count(),
                    ledger);
            assertTrue(
                    ledger.lines().anyMatch(l -> l.startsWith("| " + FLOW + "/s03") && l.contains("| Known defect ")),
                    ledger);
        }
    }
}
