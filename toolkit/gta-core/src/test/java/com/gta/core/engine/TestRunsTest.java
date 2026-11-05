package com.gta.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.run.RunResult;
import com.gta.core.run.TestOutcome;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** When a run counts as having reached the system under test, and how runs are summarised (plan §5.3, §5.4). */
class TestRunsTest {
    private static TestOutcome outcome(String o, boolean sutReached) {
        TestOutcome c = new TestOutcome();
        c.className = "com.acme.OrderIT";
        c.method = "should";
        c.outcome = o;
        c.sutReached = sutReached;
        return c;
    }

    @Test
    void shouldLockOnlyAfterTheSystemUnderTestWasReached() {
        RunResult r = new RunResult();
        assertFalse(TestRuns.reached(r), "no test ran");

        r.cases.add(outcome("error", false));
        assertFalse(TestRuns.reached(r), "failed in setup");

        r.cases.add(outcome("failed", true));
        assertTrue(TestRuns.reached(r));

        r.timedOut = true;
        assertFalse(TestRuns.reached(r));
    }

    @Test
    void shouldSummariseCountsFailuresAndPinnedDefects() {
        RunResult r = new RunResult();
        r.cases.addAll(List.of(outcome("passed", true), outcome("known-defect", true), outcome("failed", true)));
        r.compileError = true;
        r.compileMessage = "src/A.java:1 x";
        r.timedOut = true;

        Map<String, Object> s = TestRuns.summarize(r);

        assertEquals(3, s.get("tests"));
        assertEquals(1L, s.get("passed"));
        assertEquals(1L, s.get("knownDefect"));
        assertEquals(1, ((List<?>) s.get("failures")).size());
        assertEquals("src/A.java:1 x", s.get("compile"));
        assertEquals(true, s.get("timedOut"));
    }

    @Test
    void shouldRunAFailingCatchingTestOnTheBase(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root)
            throws Exception {
        java.nio.file.Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        java.nio.file.Path controller = root.resolve("src/main/java/com/acme/order/OrderController.java");
        java.nio.file.Files.createDirectories(controller.getParent());
        java.nio.file.Files.writeString(
                controller, "package com.acme.order;\npublic class OrderController { public void create() {} }\n");
        for (List<String> cmd : List.of(
                List.of("git", "init", "-q"),
                List.of("git", "config", "user.email", "t@example.com"),
                List.of("git", "config", "user.name", "t"),
                List.of("git", "add", "-A"),
                List.of("git", "commit", "-qm", "base"))) {
            com.gta.core.util.ProcessRunner.run(root, java.time.Duration.ofSeconds(30), Map.of(), cmd);
        }
        String base = new com.gta.core.util.Git(root).head();
        com.gta.core.plan.PlanItem item = new com.gta.core.plan.PlanItem();
        item.id = "order.create/s01#component-01";
        item.ref = "order.create/s01";
        item.testClass = "com.acme.order.OrderControllerIT";
        List<String> refs = List.of("method:com.acme.order.OrderController#create");
        RunResult failing = new RunResult();
        failing.cases.add(outcome("failed", true));
        RunResult passing = new RunResult();
        passing.cases.add(outcome("passed", true));
        com.gta.core.run.TestRunnerFake runner =
                new com.gta.core.run.TestRunnerFake().then(failing).then(passing);
        EngineContext ctx = new EngineContext(root, runner);

        assertEquals("unknown", ctx.runs.onBase(null, item, refs), "no base, nothing to compare");
        assertEquals(
                "new",
                ctx.runs.onBase(base, item, List.of("method:com.acme.order.OrderController#cancel")),
                "the cited code does not exist on the base");
        assertEquals("fails", ctx.runs.onBase(base, item, refs), "the failure predates the change");
        assertEquals("passes", ctx.runs.onBase(base, item, refs), "the change made it fail");
        assertEquals("scenario:order.create/s01", runner.calls().get(0).scenarioTag());
        assertFalse(java.nio.file.Files.exists(ctx.paths.work().resolve("base-run")), "the base checkout is removed");
    }
}
