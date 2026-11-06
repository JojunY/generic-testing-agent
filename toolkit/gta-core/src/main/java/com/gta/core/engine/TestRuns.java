package com.gta.core.engine;

import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.oracle.AssertionScanner;
import com.gta.core.plan.PlanItem;
import com.gta.core.profile.JavaIndex;
import com.gta.core.run.RunResult;
import com.gta.core.run.TestOutcome;
import com.gta.core.run.TestRunner;
import com.gta.core.state.StateStore;
import com.gta.core.util.Git;
import com.gta.core.util.Ids;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Every test run gtaw makes (item runs, stability runs, the gate's full run) and how their results are reported. */
final class TestRuns {
    private final EngineContext ctx;
    private final GtaPaths paths;
    private final Git git;
    private final TestRunner runner;
    private final StateStore store;
    private Set<String> productionClasses;
    private Set<String> testClasses;

    TestRuns(EngineContext ctx) {
        this.ctx = ctx;
        this.paths = ctx.paths;
        this.git = ctx.git;
        this.runner = ctx.runner;
        this.store = ctx.store;
    }

    RunResult runItem(String runId, PlanItem item, boolean variant, boolean random) {
        String tag = item.isFoundation() ? null : "scenario:" + item.ref;
        return runner.run(
                runId,
                item.id,
                new TestRunner.Options(item.testClass, tag, variant, random),
                productionClasses(),
                testClasses());
    }

    /**
     * A failing catching test run on the PR's base (plan §6): the base's production code with this branch's test tree.
     * {@code passes} means the change made it fail (a catch candidate); {@code fails} means the failure predates the
     * change; {@code new} when the code the scenario cites does not exist on the base; {@code unknown} when there is no
     * base or the test does not run there (new test support, a build problem).
     */
    String onBase(String base, PlanItem item, List<String> refs) {
        if (base == null) {
            return "unknown";
        }
        Path checkout = paths.work().resolve("base-run");
        git.removeWorktree(checkout);
        try {
            git.addWorktree(checkout, base);
            GtaPaths there = new GtaPaths(checkout).withModule(paths.module());
            com.gta.core.profile.RefResolver onBaseCode = com.gta.core.profile.RefResolver.of(there.moduleRoot());
            if (refs.stream().anyMatch(r -> onBaseCode.hash(r).isEmpty())) {
                return "new"; // the code the scenario cites does not exist on the base: nothing to compare with
            }
            copyTree(paths.moduleRoot().resolve("src/test"), there.moduleRoot().resolve("src/test"));
            String tag = "scenario:" + item.ref;
            RunResult r = ctx.runnerFor(there)
                    .run(
                            Ids.newRunId(git.head()) + "-base",
                            item.id,
                            new TestRunner.Options(item.testClass, tag, false, false),
                            productionClasses(),
                            testClasses());
            if (r.compileError || r.cases.isEmpty()) {
                return "unknown";
            }
            return r.failures().isEmpty() ? "passes" : "fails";
        } catch (GtaException | java.io.UncheckedIOException e) {
            return "unknown";
        } finally {
            git.removeWorktree(checkout);
        }
    }

    private static void copyTree(Path from, Path to) {
        if (!Files.isDirectory(from)) {
            return;
        }
        try (java.util.stream.Stream<Path> files = Files.walk(from)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Path target = to.resolve(from.relativize(f).toString());
                Path dir = target.getParent();
                if (dir != null) {
                    Files.createDirectories(dir);
                }
                Files.copy(f, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Every test tagged {@code gta}, as the gate needs it. */
    RunResult runAll() {
        return runner.run(
                Ids.newRunId(git.head()),
                null,
                new TestRunner.Options(null, "gta", false, false),
                productionClasses(),
                testClasses());
    }

    /** {@code gtaw run}: the seed tests (smoke) or every gta test, optionally one layer. */
    RunResult runTests(boolean smoke, String layer) {
        if ("system".equals(layer)) {
            throw new GtaException(
                    ErrorCode.USAGE_NOT_AVAILABLE_IN_PHASE,
                    "There is no system layer: gta tests run locally only, deployed environments are out of scope",
                    "Run the component tests; test SIT and UAT yourself");
        }
        String tag = smoke ? null : layer != null ? layer : "gta";
        String testClass = null;
        if (smoke) {
            // the seed tests of the plan: one per application (docs/guides/test-naming.md)
            testClass = String.join(
                    ",",
                    store.loadPlan().stream()
                            .filter(PlanItem::isFoundation)
                            .map(i -> i.testClass)
                            .distinct()
                            .toList());
            if (testClass.isEmpty()) {
                throw new GtaException(
                        ErrorCode.USAGE_INVALID_ARGUMENT,
                        "No seed test is planned yet",
                        "Run /gta on a flow first; its plan adds the seed test (<Application>IT)");
            }
        }
        return runner.run(
                Ids.newRunId(git.head()),
                null,
                new TestRunner.Options(testClass, tag, false, false),
                productionClasses(),
                testClasses());
    }

    AssertionScanner.ScenarioOracle scanOracle(PlanItem item) {
        return AssertionScanner.scan(
                paths.testSource(item.testClass), item.isFoundation() ? null : item.ref, paths.testResources());
    }

    /** True once a test got past setup and touched the system under test (the oracle may then be locked). */
    static boolean reached(RunResult run) {
        if (run.compileError || run.timedOut || run.cases.isEmpty()) {
            return false;
        }
        return run.cases.stream().anyMatch(c -> c.passed() || c.sutReached);
    }

    static Map<String, Object> summarize(RunResult run) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("compileError", run.compileError);
        if (run.compileError) {
            out.put("compile", run.compileMessage);
        }
        if (run.timedOut) {
            out.put("timedOut", true);
        }
        out.put("tests", run.cases.size());
        out.put(
                "passed",
                run.cases.stream().filter(c -> "passed".equals(c.outcome)).count());
        long pinned =
                run.cases.stream().filter(c -> "known-defect".equals(c.outcome)).count();
        if (pinned > 0) {
            out.put("knownDefect", pinned);
        }
        List<Map<String, Object>> failures = new ArrayList<>();
        for (TestOutcome c : run.failures()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("test", c.className + "#" + c.method);
            f.put("code", c.code);
            f.put("message", c.message);
            failures.add(f);
        }
        out.put("failures", failures);
        out.put("log", run.logFile);
        return out;
    }

    /** Production class names, parsed once per gtaw invocation (classification needs them on every run). */
    private Set<String> productionClasses() {
        if (productionClasses == null) {
            productionClasses = JavaIndex.of(paths.mainJava()).classNames();
        }
        return productionClasses;
    }

    private Set<String> testClasses() {
        if (testClasses == null) {
            testClasses = JavaIndex.of(paths.testJava()).classNames();
        }
        return testClasses;
    }
}
