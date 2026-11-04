package com.gta.core.engine;

import com.gta.core.config.GtaConfig;
import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.gate.Gate;
import com.gta.core.plan.PlanItem;
import com.gta.core.report.Reporter;
import com.gta.core.run.MavenRunner;
import com.gta.core.run.RunResult;
import com.gta.core.run.SurefireReports;
import com.gta.core.run.TestCase;
import com.gta.core.state.ItemStatus;
import com.gta.core.state.StateStore;
import com.gta.core.state.WorkState;
import com.gta.core.util.AtomicFiles;
import com.gta.core.util.Git;
import com.gta.core.util.Ids;
import com.gta.core.util.Json;
import com.gta.core.util.Proc;
import com.gta.core.util.WorkLock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gta.core.engine.NodeResults.done;

/** Session end (plan §8): stability runs, the base-anchored gate and the report / PR comment. */
final class SessionEnd {
    private final EngineContext ctx;
    private final GtaPaths paths;
    private final GtaConfig config;
    private final StateStore store;
    private final Git git;

    SessionEnd(EngineContext ctx) {
        this.ctx = ctx;
        this.paths = ctx.paths;
        this.config = ctx.config;
        this.store = ctx.store;
        this.git = ctx.git;
    }

    Map<String, Object> finishSession(WorkState st, String why, String base) {
        Map<String, Object> more = new LinkedHashMap<>();
        if (!st.sessionPassed.isEmpty()) {
            more.put("verify", verifyInternal(st, st.sessionPassed));
        }
        String baseRef = base != null ? base : defaultBase();
        Gate.Result gate = gateInternal(baseRef, List.of());
        String runId = Ids.newRunId(git.head());
        Path dir = paths.runs().resolve(runId);
        List<PlanItem> plan = store.loadPlan();
        Reporter reporter = new Reporter(paths);
        AtomicFiles.write(dir.resolve("report.md"), reporter.markdown(st, plan, gate, false));
        AtomicFiles.write(dir.resolve("pr-comment.md"), reporter.markdown(st, plan, gate, true));
        Json.writeAtomic(dir.resolve("gate.json"), gate);
        st.sessionEnded = true;
        more.put("gate", gate.decision);
        more.put("report", paths.rel(dir.resolve("report.md")));
        more.put("prComment", paths.rel(dir.resolve("pr-comment.md")));
        return done(why, more);
    }

    public Map<String, Object> verify(boolean newOnly) {
        try (WorkLock ignored = WorkLock.acquire(paths.lock())) {
            WorkState st = store.load();
            List<String> ids = newOnly ? st.sessionPassed : st.items.values().stream()
                    .filter(i -> i.status == ItemStatus.PASSED).map(i -> i.id).toList();
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
                item = ctx.items.planItem(id);
            } catch (GtaException e) {
                continue;
            }
            if (item.isFoundation() || !classesDone.add(item.testClass + "|" + item.ref)) {
                continue;
            }
            for (int i = 0; i < config.qualityGates.stabilityRepeat; i++) {
                RunResult r = ctx.items.runItem(Ids.newRunId(git.head()), item, i == 0, true);
                if (!r.allPassed()) {
                    unstable.add(id);
                    WorkState.ItemState s = st.items.get(id);
                    if (s != null && s.status == ItemStatus.PASSED) {
                        s.lastError = ctx.items.lastError(s, ErrorCode.TEST_ASSERTION_FAILED, "unstable under pass^k / Asia/Kolkata timezone / de-DE locale / random order");
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
        Json.writeAtomic(paths.runs().resolve("verify-latest.json"), out);
        return out;
    }

    public Gate.Result gate(String base, List<Path> reportDirs) {
        return gateInternal(base == null ? defaultBase() : base, reportDirs);
    }

    @SuppressWarnings("unchecked")
    Gate.Result gateInternal(String base, List<Path> reportDirs) {
        List<TestCase> cases;
        if (reportDirs == null || reportDirs.isEmpty()) {
            RunResult all = new MavenRunner(paths, config).run(Ids.newRunId(git.head()), null,
                    new MavenRunner.Options(null, "gta", false, false), ctx.items.productionClasses(), ctx.items.testClasses());
            cases = all.cases;
        } else {
            cases = SurefireReports.parse(reportDirs, paths.root().resolve("target/gta-results"));
        }
        List<String> unstable = new ArrayList<>();
        Path verify = paths.runs().resolve("verify-latest.json");
        if (Files.exists(verify)) {
            Map<String, Object> v = Json.read(verify, Map.class);
            Object u = v.get("unstable");
            if (u instanceof List<?> list) {
                list.forEach(x -> unstable.add(String.valueOf(x)));
            }
        }
        Gate.Result r = new Gate(paths, config).evaluate(base, cases, unstable);
        Json.writeAtomic(paths.runs().resolve("gate-latest.json"), r);
        return r;
    }

    public String report(String format) {
        WorkState st = store.load();
        Gate.Result gate = null;
        Path latest = paths.runs().resolve("gate-latest.json");
        if (Files.exists(latest)) {
            gate = Json.read(latest, Gate.Result.class);
        }
        return new Reporter(paths).markdown(st, store.loadPlan(), gate, "pr-comment".equals(format));
    }

    String defaultBase() {
        for (String ref : List.of("origin/main", "origin/master", "main", "master")) {
            if (Proc.run(paths.root(), Proc.cmd("git", "rev-parse", "--verify", "--quiet", ref)).ok()) {
                return ref;
            }
        }
        return "HEAD";
    }
}
