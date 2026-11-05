package com.gta.core.engine;

import static com.gta.core.engine.NodeResults.result;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.gta.core.change.Impact;
import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.oracle.OracleShard;
import com.gta.core.plan.PlanItem;
import com.gta.core.plan.Planner;
import com.gta.core.report.CaseLedger;
import com.gta.core.run.RunResult;
import com.gta.core.run.TestOutcome;
import com.gta.core.spec.Ref;
import com.gta.core.spec.Spec;
import com.gta.core.spec.SpecParser;
import com.gta.core.util.AtomicFiles;
import com.gta.core.util.Git;
import com.gta.core.util.Json;
import com.gta.core.util.WorkLock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Keeps the test case ledgers ({@code <flow>.cases.md}, plan §10.6) in step with specs and test results. Every change is
 * written by gtaw at a fixed point and committed with what caused it: the approval, the item commit, or the session end.
 */
final class CaseLedgers {
    private final EngineContext ctx;
    private final GtaPaths paths;
    private final Git git;

    CaseLedgers(EngineContext ctx) {
        this.ctx = ctx;
        this.paths = ctx.paths;
        this.git = ctx.git;
    }

    /** After a spec is approved: rows for new scenarios, review for changed ones, retirement for removed ones. */
    List<String> afterApproval(String flowId) {
        Spec spec = SpecParser.parse(paths.spec(flowId));
        CaseLedger ledger = CaseLedger.read(paths.cases(flowId), flowId);
        ledger.syncWithSpec(spec, String.valueOf(paths.spec(flowId).getFileName()), lockedRevisions(flowId));
        return write(ledger);
    }

    /** After an item's test is committed: the automation and its passing (or pinned) result, in the same commit. */
    List<String> afterItemCommit(PlanItem item, String runId, boolean knownDefect) {
        if (item.isFoundation()) {
            return List.of();
        }
        CaseLedger ledger = CaseLedger.read(paths.cases(item.flowId), item.flowId);
        String result =
                knownDefect ? "Known defect " + today() + " " + sha7() : CaseLedger.result(true, today(), sha7());
        ledger.recordResult(item.ref, automation(item, runId), result, CaseLedger.ACTIVE);
        return write(ledger);
    }

    /**
     * After the session's full run: the latest result of every automated case. A failing or unstable case needs review;
     * only an item commit makes it active again.
     *
     * @return the paths written, and the row changes for the report
     */
    Map<String, Object> afterSession(List<TestOutcome> cases, List<String> unstableItems) {
        Map<String, Boolean> passedByScenario = new LinkedHashMap<>();
        Map<String, String> testByScenario = new LinkedHashMap<>();
        Set<String> pinned = new LinkedHashSet<>();
        for (TestOutcome c : cases) {
            String scenario = c.scenarioId();
            if (scenario == null) {
                continue;
            }
            boolean ok = c.passed() || "known-defect".equals(c.outcome);
            if ("known-defect".equals(c.outcome)) {
                pinned.add(scenario); // a pinned defect stays a known defect, never "Pass"
            }
            passedByScenario.merge(scenario, ok, Boolean::logicalAnd);
            testByScenario.putIfAbsent(scenario, c.className + "#" + c.method);
        }
        Set<String> unstable = new LinkedHashSet<>();
        unstableItems.forEach(i -> unstable.add(i.contains("#") ? i.substring(0, i.indexOf('#')) : i));
        List<String> written = new ArrayList<>();
        List<CaseLedger.Change> changes = new ArrayList<>();
        for (CaseLedger ledger : all()) {
            for (CaseLedger.Row row : ledger.rows()) {
                Boolean passed = passedByScenario.get(row.id);
                if (passed == null) {
                    continue;
                }
                boolean ok = passed && !unstable.contains(row.id);
                String result = ok && pinned.contains(row.id)
                        ? "Known defect " + today() + " " + sha7()
                        : CaseLedger.result(ok, today(), sha7());
                ledger.recordResult(row.id, testByScenario.get(row.id), result, ok ? null : CaseLedger.NEEDS_REVIEW);
            }
            changes.addAll(ledger.changes());
            if (!ledger.changes().isEmpty()) {
                written.addAll(write(ledger));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paths", written);
        out.put("changes", changes);
        return out;
    }

    /** {@code gtaw cases --rebuild}: ledgers for every approved spec, e.g. the first time on an existing service. */
    Map<String, Object> rebuild() {
        try (WorkLock ignored = WorkLock.acquire(paths.lock())) {
            List<String> written = new ArrayList<>();
            for (String flowId : approvedFlows()) {
                written.addAll(afterApproval(flowId));
                CaseLedger ledger = CaseLedger.read(paths.cases(flowId), flowId);
                // automation known from the committed locks; results come with the next run
                OracleShard.read(paths.oracleShard(flowId)).forEach((itemId, e) -> {
                    String scenario = itemId.substring(0, itemId.indexOf('#'));
                    if (!itemId.contains("#foundation") && ledger.row(scenario).isPresent()) {
                        CaseLedger.Row row = ledger.row(scenario).get();
                        ledger.recordResult(scenario, e.testClass, row.result, null);
                    }
                });
                written.addAll(write(ledger));
            }
            Map<String, Object> more = new LinkedHashMap<>();
            more.put("files", written.stream().distinct().toList());
            return result("ledgers rebuilt for " + approvedFlows().size() + " approved spec(s); commit them", more);
        }
    }

    /** {@code gtaw cases}: the overview, read-only. */
    Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> flows = new ArrayList<>();
        for (CaseLedger l : all()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("flowId", l.flowId());
            f.put("cases", l.rows().size() + l.manualRows().size());
            f.put(
                    "needsReview",
                    l.rows().stream()
                            .filter(r -> CaseLedger.NEEDS_REVIEW.equals(r.status))
                            .map(r -> r.id)
                            .toList());
            f.put(
                    "retired",
                    l.rows().stream()
                            .filter(r -> CaseLedger.RETIRED.equals(r.status))
                            .map(r -> r.id)
                            .toList());
            flows.add(f);
        }
        out.put("flows", flows);
        out.put("index", paths.rel(paths.casesIndex()));
        return out;
    }

    /** Retired cases whose tests still exist: listed in every report until a human confirms them. */
    List<String> retired() {
        List<String> out = new ArrayList<>();
        for (CaseLedger l : all()) {
            l.rows().stream().filter(r -> CaseLedger.RETIRED.equals(r.status)).forEach(r -> out.add(r.id));
        }
        return out;
    }

    /**
     * {@code gtaw cases confirm-retired}: a human's batch confirmation. Deletes the tests of the confirmed retired cases
     * (only the methods tagged with the scenario; a class left without tests is deleted), their lock entries and rows,
     * and commits it. Never runs on its own.
     */
    Map<String, Object> confirmRetired(List<String> ids, boolean all) {
        try (WorkLock ignored = WorkLock.acquire(paths.lock())) {
            List<String> retired = retired();
            List<String> confirmed = all ? retired : ids;
            for (String id : confirmed) {
                if (!retired.contains(id)) {
                    throw new GtaException(
                            ErrorCode.USAGE_UNKNOWN_ITEM,
                            id + " is not a retired case",
                            "Only cases with Status Retired can be confirmed; see ./gtaw cases");
                }
            }
            Set<String> touched = new LinkedHashSet<>();
            for (String id : confirmed) {
                String flowId = id.substring(0, id.indexOf('/'));
                Path shardPath = paths.oracleShard(flowId);
                Map<String, OracleShard.Entry> shard = OracleShard.read(shardPath);
                for (String itemId : List.copyOf(shard.keySet())) {
                    if (itemId.startsWith(id + "#")) {
                        touched.addAll(deleteTestMethods(shard.get(itemId).testClass, id));
                        shard.remove(itemId);
                        touched.add(paths.rel(shardPath));
                    }
                }
                if (touched.contains(paths.rel(shardPath))) {
                    OracleShard.write(shardPath, shard);
                }
                CaseLedger ledger = CaseLedger.read(paths.cases(flowId), flowId);
                ledger.remove(id);
                touched.addAll(write(ledger));
            }
            if (!touched.isEmpty()) {
                ctx.commit(
                        List.copyOf(touched),
                        "test: delete retired cases " + String.join(", ", confirmed) + "\n\nGta-Retired: " + confirmed);
            }
            Map<String, Object> more = new LinkedHashMap<>();
            more.put("deleted", confirmed);
            more.put("files", List.copyOf(touched));
            return result(confirmed.size() + " retired case(s) deleted and committed", more);
        }
    }

    /**
     * {@code gtaw impact --base <ref>}: the cases whose code changed since {@code base} need review; the regression scope
     * is those cases plus every P0 case.
     */
    Map<String, Object> impact(String base) {
        try (WorkLock ignored = WorkLock.acquire(paths.lock())) {
            return impactUnlocked(base);
        }
    }

    /** {@link #impact(String)} for a caller that already holds the work lock (PR mode in {@code next}). */
    Map<String, Object> impactUnlocked(String base) {
        Set<String> changed = new LinkedHashSet<>(git.changedFiles(base));
        changed.addAll(git.dirtyPaths());
        // a scenario is affected through the classes its spec cites, the classes they inject (bean graph), and
        // the config keys it cites whose value changed
        Map<String, String> affectedClasses = Impact.affectedClasses(
                com.gta.core.profile.JavaIndex.of(paths.mainJava()), Impact.changedClasses(paths, changed));
        Set<String> changedKeys = Impact.changedConfigKeys(git, paths, base, changed);
        Map<String, String> reasons = new LinkedHashMap<>();
        List<String> affected = new ArrayList<>();
        List<String> p0 = new ArrayList<>();
        Set<String> tests = new LinkedHashSet<>();
        for (String flowId : approvedFlows()) {
            Spec spec = SpecParser.parse(paths.spec(flowId));
            CaseLedger ledger = CaseLedger.read(paths.cases(flowId), flowId);
            for (Spec.Requirement r : spec.requirements) {
                String reason = null;
                for (Ref ref : r.refs().stream()
                        .map(Ref::parse)
                        .flatMap(Optional::stream)
                        .toList()) {
                    if (ref.isCode() && affectedClasses.containsKey(ref.target())) {
                        String cause = affectedClasses.get(ref.target());
                        reason = cause.equals(ref.target())
                                ? ref.target() + " changed"
                                : cause + " changed (reached through " + ref.target() + ")";
                    } else if ("config".equals(ref.kind()) && changedKeys.contains(ref.target())) {
                        reason = "config " + ref.target() + " changed";
                    } else if ("file".equals(ref.kind()) && changed.contains(paths.inModule(ref.target()))) {
                        reason = ref.target() + " changed";
                    }
                    if (reason != null) {
                        break;
                    }
                }
                boolean touched = reason != null;
                for (Spec.Scenario s : r.scenarios) {
                    if (s.id() == null) {
                        continue;
                    }
                    if (touched) {
                        ledger.markForReview(s.id());
                        affected.add(s.id());
                        reasons.put(s.id(), reason);
                    }
                }
            }
            for (CaseLedger.Row row : ledger.rows()) {
                if ("P0".equals(row.priority) && !CaseLedger.RETIRED.equals(row.status)) {
                    p0.add(row.id);
                }
                if ((affected.contains(row.id) || "P0".equals(row.priority))
                        && !CaseLedger.NOT_AUTOMATED.equals(row.automation)) {
                    tests.add(
                            row.automation.contains("#")
                                    ? row.automation.substring(0, row.automation.indexOf('#'))
                                    : row.automation);
                }
            }
            if (!ledger.changes().isEmpty()) {
                write(ledger);
            }
        }
        Set<String> scope = new LinkedHashSet<>(affected);
        scope.addAll(p0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("base", base);
        out.put("needsReview", affected);
        out.put("reasons", reasons);
        out.put("regressionScope", List.copyOf(scope));
        out.put("testClasses", List.copyOf(tests));
        out.put("tags", scope.stream().map(id -> "scenario:" + id).toList());
        out.put("changedClasses", List.copyOf(Impact.changedClasses(paths, changed)));
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private List<String> write(CaseLedger ledger) {
        ledger.write(paths.cases(ledger.flowId()));
        AtomicFiles.write(paths.casesIndex(), CaseLedger.index(all()));
        return List.of(paths.rel(paths.cases(ledger.flowId())), paths.rel(paths.casesIndex()));
    }

    private List<CaseLedger> all() {
        List<CaseLedger> out = new ArrayList<>();
        Path specs = paths.specs();
        if (!Files.isDirectory(specs)) {
            return out;
        }
        try (Stream<Path> files = Files.walk(specs, 2)) {
            for (Path f : files.filter(p -> String.valueOf(p.getFileName()).endsWith(".cases.md"))
                    .sorted()
                    .toList()) {
                GtaPaths.flowIdOfCasesPath(paths.rel(f)).ifPresent(flowId -> out.add(CaseLedger.read(f, flowId)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private List<String> approvedFlows() {
        List<String> out = new ArrayList<>();
        Path specs = paths.specs();
        if (!Files.isDirectory(specs)) {
            return out;
        }
        try (Stream<Path> files = Files.walk(specs, 2)) {
            for (Path f : files.filter(p -> String.valueOf(p.getFileName()).endsWith(".spec.md"))
                    .sorted()
                    .toList()) {
                Optional<String> flowId = GtaPaths.flowIdOfSpecPath(paths.rel(f));
                if (flowId.isPresent() && "approved".equals(SpecParser.parse(f).status())) {
                    out.add(flowId.get());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** Scenario id → revision its committed test was locked on. */
    private Map<String, String> lockedRevisions(String flowId) {
        Map<String, String> out = new TreeMap<>();
        OracleShard.read(paths.oracleShard(flowId)).forEach((itemId, e) -> {
            if (!itemId.contains("#foundation")) {
                out.put(itemId.substring(0, itemId.indexOf('#')), e.scenarioRevision);
            }
        });
        return out;
    }

    /** {@code Class#method} of the test that ran the scenario, from the run's evidence; the class alone otherwise. */
    private String automation(PlanItem item, String runId) {
        Path run = paths.runs().resolve(runId).resolve("run.json");
        if (runId != null && Files.exists(run)) {
            RunResult r = Json.read(run, RunResult.class);
            for (TestOutcome c : r.cases) {
                if (item.ref.equals(c.scenarioId())) {
                    return c.className + "#" + c.method;
                }
            }
        }
        return item.testClass;
    }

    /** Removes the methods tagged with the scenario; deletes the file when no test method is left. */
    private List<String> deleteTestMethods(String testClass, String scenarioId) {
        if (testClass == null || !Files.exists(paths.testSource(testClass))) {
            return List.of();
        }
        Path file = paths.testSource(testClass);
        try {
            CompilationUnit cu = LexicalPreservingPrinter.setup(StaticJavaParser.parse(file));
            for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
                if (m.getAnnotations().stream().anyMatch(a -> tags(a, "scenario:" + scenarioId))) {
                    m.remove();
                }
            }
            boolean anyTestLeft = cu.findAll(MethodDeclaration.class).stream()
                    .anyMatch(m -> m.getAnnotationByName("Test").isPresent()
                            || m.getAnnotationByName("ParameterizedTest").isPresent());
            if (anyTestLeft) {
                AtomicFiles.write(file, LexicalPreservingPrinter.print(cu));
            } else {
                Files.delete(file);
            }
            List<String> out = new ArrayList<>(List.of(paths.rel(file)));
            Path fixtures = paths.moduleRoot().resolve(Planner.fixtureFolder(testClass));
            String prefix = scenarioId.substring(scenarioId.indexOf('/') + 1) + ".";
            if (Files.isDirectory(fixtures)) {
                try (Stream<Path> fs = Files.list(fixtures)) {
                    for (Path f : fs.filter(p -> String.valueOf(p.getFileName()).startsWith(prefix))
                            .toList()) {
                        Files.delete(f);
                        out.add(paths.rel(f));
                    }
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean tags(AnnotationExpr a, String tag) {
        return a.getNameAsString().equals("Tag") && a.toString().contains("\"" + tag + "\"");
    }

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private String sha7() {
        String head = git.head();
        return head.substring(0, Math.min(7, head.length()));
    }

    /** Ledger changes of one session as report lines (plan §10.6). */
    static String reportSection(List<CaseLedger.Change> changes, List<String> retired) {
        StringBuilder b = new StringBuilder();
        if (changes.isEmpty() && retired.isEmpty()) {
            return "";
        }
        b.append("\n## Test case ledger\n\n");
        if (!changes.isEmpty()) {
            b.append("Rows changed this session:\n\n");
            changes.forEach(c -> b.append("- `")
                    .append(c.id())
                    .append("`: ")
                    .append(c.before())
                    .append(" → ")
                    .append(c.after())
                    .append('\n'));
            b.append('\n');
        }
        if (!retired.isEmpty()) {
            b.append(
                    "Retired cases whose tests still run (confirm to delete them: `./gtaw cases confirm-retired <ids>` or `--all`):\n\n");
            retired.forEach(id -> b.append("- `").append(id).append("`\n"));
        }
        return b.toString();
    }
}
