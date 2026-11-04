package com.gta.core.engine;

import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.oracle.AssertionScanner;
import com.gta.core.plan.PlanItem;
import com.gta.core.profile.JavaIndex;
import com.gta.core.run.RunResult;
import com.gta.core.run.TestCase;
import com.gta.core.run.TestRunner;
import com.gta.core.util.Git;
import com.gta.core.util.Ids;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Every test run gtaw makes (item runs, stability runs, the gate's full run) and how their results are reported. */
final class TestRuns {
    private final GtaPaths paths;
    private final Git git;
    private final TestRunner runner;
    private Set<String> productionClasses;
    private Set<String> testClasses;

    TestRuns(EngineContext ctx) {
        this.paths = ctx.paths;
        this.git = ctx.git;
        this.runner = ctx.runner;
    }

    RunResult runItem(String runId, PlanItem item, boolean variant, boolean random) {
        String tag = item.isFoundation() ? null : "scenario:" + item.ref;
        return runner.run(runId, item.id, new TestRunner.Options(item.testClass, tag, variant, random), productionClasses(), testClasses());
    }

    /** Every test tagged {@code gta}, as the gate needs it. */
    RunResult runAll() {
        return runner.run(Ids.newRunId(git.head()), null, new TestRunner.Options(null, "gta", false, false), productionClasses(), testClasses());
    }

    /** {@code gtaw run}: the seed tests (smoke) or every gta test, optionally one layer. */
    RunResult runTests(boolean smoke, String layer) {
        if ("system".equals(layer)) {
            throw new GtaException(ErrorCode.USAGE_NOT_AVAILABLE_IN_PHASE, "The system layer arrives in P4", "Run component tests instead");
        }
        String tag = smoke ? null : layer != null ? layer : "gta";
        return runner.run(Ids.newRunId(git.head()), null, new TestRunner.Options(smoke ? "*SeedIT" : null, tag, false, false),
                productionClasses(), testClasses());
    }

    AssertionScanner.ScenarioOracle scanOracle(PlanItem item) {
        return AssertionScanner.scan(paths.testSource(item.testClass), item.isFoundation() ? null : item.ref, paths.testResources());
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
        out.put("passed", run.cases.stream().filter(c -> "passed".equals(c.outcome)).count());
        long pinned = run.cases.stream().filter(c -> "known-defect".equals(c.outcome)).count();
        if (pinned > 0) {
            out.put("knownDefect", pinned);
        }
        List<Map<String, Object>> failures = new ArrayList<>();
        for (TestCase c : run.failures()) {
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
