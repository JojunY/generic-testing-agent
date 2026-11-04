package com.gta.core.engine;

import static com.gta.core.engine.NodeResults.done;

import com.gta.core.config.GtaConfig;
import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.gate.Gate;
import com.gta.core.plan.PlanItem;
import com.gta.core.report.CaseLedger;
import com.gta.core.report.Reporter;
import com.gta.core.run.MavenRunner;
import com.gta.core.run.RunResult;
import com.gta.core.run.SurefireReportParser;
import com.gta.core.run.TestOutcome;
import com.gta.core.state.ItemStatus;
import com.gta.core.state.StateStore;
import com.gta.core.state.WorkState;
import com.gta.core.util.AtomicFiles;
import com.gta.core.util.Git;
import com.gta.core.util.Ids;
import com.gta.core.util.Json;
import com.gta.core.util.ProcessRunner;
import com.gta.core.util.WorkLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Session end (plan §8): stability runs, the base-anchored gate and the report / PR comment. */
final class SessionEnd {
    private final EngineContext ctx;
    private final GtaPaths paths;
    private final GtaConfig config;
    private final StateStore store;
    private final Git git;
    /** The test outcomes of the last gate run; the session end records them in the case ledgers. */
    private List<TestOutcome> lastCases = List.of();

    SessionEnd(EngineContext ctx) {
        this.ctx = ctx;
        this.paths = ctx.paths;
        this.config = ctx.config;
        this.store = ctx.store;
        this.git = ctx.git;
    }

    Map<String, Object> finishSession(WorkState st, String why, String base) {
        Map<String, Object> more = new LinkedHashMap<>();
        List<String> unstable = new ArrayList<>();
        if (!st.sessionPassed.isEmpty()) {
            Map<String, Object> verify = verifyInternal(st, st.sessionPassed);
            more.put("verify", verify);
            if (verify.get("unstable") instanceof List<?> u) {
                u.forEach(x -> unstable.add(String.valueOf(x)));
            }
        }
        String baseRef = base != null ? base : defaultBase();
        Gate.Result gate = gateInternal(baseRef, List.of());
        Map<String, Object> ledgers = ctx.cases.afterSession(lastCases, unstable);
        String runId = Ids.newRunId(git.head());
        Path dir = paths.runs().resolve(runId);
        List<PlanItem> plan = store.loadPlan();
        Reporter reporter = new Reporter(paths);
        @SuppressWarnings("unchecked")
        List<CaseLedger.Change> changes = (List<CaseLedger.Change>) ledgers.get("changes");
        String report =
                reporter.markdown(st, plan, gate, false) + CaseLedgers.reportSection(changes, ctx.cases.retired());
        // the report is the session's audit trail: tracked, next to nothing temporary (plan §10.1)
        Path reportFile = paths.reports().resolve(LocalDate.now(ZoneOffset.UTC) + "-" + st.session.id + ".md");
        AtomicFiles.write(reportFile, report);
        AtomicFiles.write(dir.resolve("pr-comment.md"), reporter.markdown(st, plan, gate, true));
        Json.writeAtomic(dir.resolve("gate.json"), gate);
        @SuppressWarnings("unchecked")
        List<String> files = new ArrayList<>((List<String>) ledgers.get("paths"));
        files.add(paths.rel(reportFile));
        ctx.commit(files.stream().distinct().toList(), "docs(gta): session report " + st.session.id);
        st.sessionEnded = true;
        more.put("gate", gate.decision);
        more.put("report", paths.rel(reportFile));
        more.put("prComment", paths.rel(dir.resolve("pr-comment.md")));
        return done(why, more);
    }

    public Map<String, Object> verify(boolean newOnly) {
        try (WorkLock ignored = WorkLock.acquire(paths.lock())) {
            WorkState st = store.load();
            List<String> ids = newOnly
                    ? st.sessionPassed
                    : st.items.values().stream()
                            .filter(i -> i.status == ItemStatus.PASSED)
                            .map(i -> i.id)
                            .toList();
            Map<String, Object> out = verifyInternal(st, new ArrayList<>(ids));
            store.save(st);
            return out;
        }
    }

    Map<String, Object> verifyInternal(WorkState st, List<String> ids) {
        List<String> unstable = new ArrayList<>();
        Set<String> classesDone = new LinkedHashSet<>();
        for (String id : ids) {
            PlanItem item;
            try {
                item = store.planItem(id);
            } catch (GtaException e) {
                continue;
            }
            if (item.isFoundation() || !classesDone.add(item.testClass + "|" + item.ref)) {
                continue;
            }
            for (int i = 0; i < config.qualityGates.stabilityRepeat; i++) {
                RunResult r = ctx.runs.runItem(Ids.newRunId(git.head()), item, i == 0, true);
                if (r.timedOut || !r.allPassed()) {
                    unstable.add(id);
                    WorkState.ItemState s = st.items.get(id);
                    if (s != null && s.status == ItemStatus.PASSED) {
                        s.lastError = ItemWorkflow.lastError(
                                s,
                                ErrorCode.TEST_ASSERTION_FAILED,
                                "unstable under pass^k / " + MavenRunner.stabilityVariantLabel(config.qualityGates)
                                        + " / random order");
                        s.verdict = "flaky";
                        st.move(s, ItemStatus.TRIAGING);
                    }
                    break;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checked", classesDone.size());
        out.put("repeat", config.qualityGates.stabilityRepeat);
        out.put("unstable", unstable);
        Json.writeAtomic(paths.verifyLatest(), out);
        return out;
    }

    public Gate.Result gate(String base, List<Path> reportDirs) {
        return gateInternal(base == null ? defaultBase() : base, reportDirs);
    }

    @SuppressWarnings("unchecked")
    Gate.Result gateInternal(String base, List<Path> reportDirs) {
        List<TestOutcome> cases;
        lastCases = List.of();
        if (reportDirs == null || reportDirs.isEmpty()) {
            cases = ctx.runs.runAll().cases;
        } else {
            cases = SurefireReportParser.parse(reportDirs, paths.gtaResults());
        }
        List<String> unstable = new ArrayList<>();
        Path verify = paths.verifyLatest();
        if (Files.exists(verify)) {
            Map<String, Object> v = Json.read(verify, Map.class);
            Object u = v.get("unstable");
            if (u instanceof List<?> list) {
                list.forEach(x -> unstable.add(String.valueOf(x)));
            }
        }
        lastCases = cases;
        Gate.Result r = new Gate(paths, config).evaluate(base, cases, unstable);
        Json.writeAtomic(paths.gateLatest(), r);
        return r;
    }

    /** Read-only: never moves or rewrites working-state files. */
    public String report(String format) {
        WorkState st = store.peek();
        Gate.Result gate = null;
        Path latest = paths.gateLatest();
        if (Files.exists(latest)) {
            gate = Json.read(latest, Gate.Result.class);
        }
        return new Reporter(paths).markdown(st, store.peekPlan(), gate, "pr-comment".equals(format));
    }

    String defaultBase() {
        for (String ref : List.of("origin/main", "origin/master", "main", "master")) {
            if (ProcessRunner.run(paths.root(), ProcessRunner.cmd("git", "rev-parse", "--verify", "--quiet", ref))
                    .ok()) {
                return ref;
            }
        }
        return "HEAD";
    }
}
