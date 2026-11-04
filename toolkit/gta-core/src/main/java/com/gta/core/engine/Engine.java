package com.gta.core.engine;

import static com.gta.core.engine.NodeResults.answerNode;
import static com.gta.core.engine.NodeResults.done;
import static com.gta.core.engine.NodeResults.node;
import static com.gta.core.engine.NodeResults.result;

import com.gta.core.config.GtaConfig;
import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.gate.Gate;
import com.gta.core.oracle.AssertionScanner;
import com.gta.core.oracle.OracleLock;
import com.gta.core.oracle.OracleShard;
import com.gta.core.plan.PlanItem;
import com.gta.core.plan.Planner;
import com.gta.core.profile.Profiler;
import com.gta.core.profile.ServiceProfile;
import com.gta.core.registry.CapabilityRegistry;
import com.gta.core.run.RunResult;
import com.gta.core.run.TestRunner;
import com.gta.core.spec.AnswerParser;
import com.gta.core.spec.Ref;
import com.gta.core.spec.Spec;
import com.gta.core.spec.SpecEditor;
import com.gta.core.spec.SpecParser;
import com.gta.core.state.StateStore;
import com.gta.core.state.WorkState;
import com.gta.core.util.Git;
import com.gta.core.util.Ids;
import com.gta.core.util.Json;
import com.gta.core.util.WorkLock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The deterministic blueprint (plan §4.2): {@code next → dispatch → check* → submit}. This facade is the only public entry
 * point; it routes to {@link SpecWorkflow}, {@link ItemWorkflow}, {@link SessionEnd} and {@link SessionManager}, which do
 * every state change, test run, classification, lock and commit. Agents only write specs and tests and give verdicts.
 */
public final class Engine {
    private final EngineContext ctx;
    private final GtaPaths paths;
    private final GtaConfig config;
    private final CapabilityRegistry registry;
    private final StateStore store;
    private final Git git;

    public Engine(Path root) {
        this(root, null);
    }

    /** For tests of gta: a fake {@link TestRunner} instead of Maven. */
    Engine(Path root, TestRunner runner) {
        this.ctx = new EngineContext(root, runner);
        this.paths = ctx.paths;
        this.config = ctx.config;
        this.registry = ctx.registry;
        this.store = ctx.store;
        this.git = ctx.git;
    }

    public Map<String, Object> next(String mode, String target, String base, boolean takeover) {
        git.requireRepo();
        try (WorkLock ignored = WorkLock.acquire(paths.lock())) {
            WorkState st = store.load();
            Map<String, Object> recovered = ctx.sessions.touchSession(st, takeover);
            if (mode != null) {
                st.mode = mode;
            }
            ServiceProfile profile = ensureProfile(target != null);
            if (target != null) {
                st.activeFlow = resolveTarget(target, profile);
                st.pending = null;
            }
            Map<String, Object> out = step(st, profile, base);
            if (List.of("analyze", "author", "review").contains(String.valueOf(out.get("node")))) {
                out.put("dispatch", config.dispatch); // subagent | inline: how the orchestrator runs the node
            }
            if (!recovered.isEmpty()) {
                out.put("recovered", recovered);
            }
            store.save(st);
            return out;
        }
    }

    private Map<String, Object> step(WorkState st, ServiceProfile profile, String base) {
        if (st.pending != null) {
            return answerNode(st.pending);
        }
        if (st.activeFlow == null) {
            return chooseFlow(profile);
        }
        String flowId = st.activeFlow;
        Path specPath = paths.spec(flowId);
        ServiceProfile.EntryPoint entry = profile.entrypoints.stream()
                .filter(e -> e.flowId.equals(flowId))
                .findFirst()
                .orElse(null);
        WorkState.SpecWork sw = st.specWork.computeIfAbsent(flowId, k -> new WorkState.SpecWork());
        String capability = Files.exists(specPath)
                ? SpecParser.parse(specPath).capability()
                : entry != null ? entry.capability : "rest-api";
        CapabilityRegistry.Capability cap = registry.capability(capability);
        boolean explainOnly = !cap.activeIn(CapabilityRegistry.CURRENT_PHASE);

        if (!Files.exists(specPath)
                || sw.needsRevision
                || "draft".equals(SpecParser.parse(specPath).status())) {
            return ctx.specs.analyzeNode(st, sw, flowId, entry, capability, List.of());
        }
        Spec spec = SpecParser.parse(specPath);
        if ("approved".equals(spec.status())) {
            List<String> staleRefs = ctx.specs.staleRefs(spec);
            if (!staleRefs.isEmpty()) {
                ctx.specs.writeSpec(
                        specPath,
                        new SpecEditor(spec.text).setHeader("status", "stale").text());
                return ctx.specs.analyzeNode(
                        st,
                        sw,
                        flowId,
                        entry,
                        capability,
                        staleRefs.stream()
                                .map(r -> "ref changed since approval: " + r + " — re-check the affected scenarios")
                                .toList());
            }
        }
        if ("stale".equals(spec.status())) {
            return ctx.specs.analyzeNode(
                    st, sw, flowId, entry, capability, List.of("spec is stale: production code it references changed"));
        }
        if ("in-review".equals(spec.status())) {
            WorkState.Pending p = new WorkState.Pending();
            p.flowId = flowId;
            if (!spec.openQuestions().isEmpty()) {
                p.type = "answer";
                p.round = spec.questionRound == null ? spec.rev() : spec.questionRound;
                p.prompt = ctx.prompts.renderQuestions(spec, p.round);
            } else {
                p.type = "approve";
                p.rev = spec.rev();
                p.prompt = ctx.prompts.renderApproval(spec);
            }
            st.pending = p;
            return answerNode(p);
        }
        if (!"approved".equals(spec.status())) {
            return done("Flow " + flowId + " is " + spec.status() + "; nothing to do", null);
        }
        if (explainOnly) {
            return done(
                    "Capability " + capability + " is explain-only in " + CapabilityRegistry.CURRENT_PHASE
                            + " (active from " + cap.since + "). The approved spec is kept as documentation.",
                    null);
        }
        if ("explain".equals(st.mode)) {
            st.mode = "flow"; // explain turns into flow mode once the human approved the spec (plan §6)
        }
        List<PlanItem> flowItems = ctx.items.syncPlan(st, spec, profile);
        ItemWorkflow.Dispatch next = ctx.items.dispatch(st, spec, profile, flowItems);
        return next.node() != null ? next.node() : ctx.sessionEnd.finishSession(st, next.sessionOverReason(), base);
    }

    private Map<String, Object> chooseFlow(ServiceProfile profile) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        profile.entrypoints.stream()
                .sorted(Comparator.comparingInt((ServiceProfile.EntryPoint e) -> -e.risk))
                .limit(8)
                .forEach(e -> {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("flowId", e.flowId);
                    c.put("risk", e.risk);
                    c.put("entry", e.ref + (e.httpMethod != null ? " (" + e.httpMethod + " " + e.path + ")" : ""));
                    c.put(
                            "spec",
                            Files.exists(paths.spec(e.flowId))
                                    ? SpecParser.parse(paths.spec(e.flowId)).status()
                                    : "none");
                    candidates.add(c);
                });
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put(
                "message",
                "No flow selected. Ask the human which flow to work on, then run ./gtaw next --target <flowId>");
        extra.put("candidates", candidates);
        return node("done", extra);
    }

    public Map<String, Object> answer(String text) {
        try (WorkLock ignored = WorkLock.acquire(paths.lock())) {
            WorkState st = store.load();
            if (st.pending == null) {
                throw new GtaException(
                        ErrorCode.STATE_NOTHING_PENDING,
                        "Nothing is waiting for an answer",
                        "Run ./gtaw next; answers are only accepted while an 'answer' node is open");
            }
            WorkState.Pending p = st.pending;
            Map<String, Object> out =
                    switch (p.type) {
                        case "answer" -> ctx.specs.applyAnswers(st, p, AnswerParser.parse(text), text);
                        case "approve" -> approvalOrChangeRequest(st, p, text);
                        case "defect", "escalation" -> ctx.items.applyDecision(st, p, AnswerParser.parse(text), text);
                        default -> throw new GtaException(
                                ErrorCode.INTERNAL_ERROR, "Unknown pending type " + p.type, "Run ./gtaw abort");
                    };
            store.save(st);
            return out;
        }
    }

    /** Only an exact "approve flow@rev" approves; any other reply is the human asking for changes. */
    private Map<String, Object> approvalOrChangeRequest(WorkState st, WorkState.Pending p, String text) {
        AnswerParser.Parsed parsed;
        try {
            parsed = AnswerParser.parse(text);
        } catch (GtaException notAnApproval) {
            return ctx.specs.requestChanges(st, p, text);
        }
        return parsed instanceof AnswerParser.Approval
                ? ctx.specs.applyApproval(st, p, parsed, text)
                : ctx.specs.requestChanges(st, p, text);
    }

    /** Read-only: never moves or rewrites working-state files. */
    public Map<String, Object> status() {
        WorkState st = store.peek();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", st.session == null ? null : st.session.id);
        out.put("mode", st.mode);
        out.put("activeFlow", st.activeFlow);
        if (st.pending != null) {
            out.put("waitingForYou", st.pending.prompt);
        }
        List<Map<String, Object>> flows = new ArrayList<>();
        if (Files.isDirectory(paths.specs())) {
            try (Stream<Path> files = Files.walk(paths.specs())) {
                for (Path f : files.filter(p -> p.toString().endsWith(".spec.md"))
                        .sorted()
                        .toList()) {
                    Spec s = SpecParser.parse(f);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("flowId", s.flowId());
                    m.put("status", s.status());
                    m.put("rev", s.rev());
                    m.put("openQuestions", s.openQuestions().size());
                    flows.add(m);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        out.put("flows", flows);
        List<Map<String, Object>> items = new ArrayList<>();
        for (WorkState.ItemState s : st.items.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id);
            m.put("status", s.status.id());
            m.put("attempts", s.attempts);
            if (s.reason != null) {
                m.put("reason", s.reason);
            }
            items.add(m);
        }
        out.put("items", items);
        if (!st.pendingReview.isEmpty()) {
            out.put("toReconfirm", st.pendingReview);
        }
        return out;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /**
     * Discards the working state of one flow, or of everything when {@code flowId} is null. Specs, shards, committed
     * tests, run evidence and the backups in {@code .gta/work/recover/} are kept.
     */
    public Map<String, Object> abort(String flowId) {
        try (WorkLock ignored = WorkLock.acquire(paths.lock())) {
            WorkState st = store.load();
            if (flowId == null) {
                store.save(new WorkState());
                Files.deleteIfExists(paths.plan());
                // brief numbers restart with the new state, so old briefs and snapshots would be misleading;
                // .gta/work/recover/ is kept: it may hold the only copy of files gtaw reverted or deleted
                for (Path dir : List.of(paths.briefs(), paths.snapshots())) {
                    deleteTree(dir);
                }
                return result(
                        "working state discarded; specs, shards, committed tests, run evidence and "
                                + paths.rel(paths.recover()) + " (backups of reverted files) are kept",
                        null);
            }
            List<PlanItem> plan = store.loadPlan();
            plan.stream().filter(i -> i.flowId.equals(flowId)).forEach(i -> st.items.remove(i.id));
            plan.removeIf(i -> i.flowId.equals(flowId));
            store.savePlan(plan);
            st.specWork.remove(flowId);
            if (st.pending != null && flowId.equals(st.pending.flowId)) {
                st.pending = null;
            }
            if (flowId.equals(st.activeFlow)) {
                st.activeFlow = null;
            }
            store.save(st);
            return result("working state of " + flowId + " discarded", null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Re-locks a committed oracle after an intended test change (human only). Works from the committed shard alone, so
     * it also runs on a fresh checkout without working state.
     */
    public Map<String, Object> relock(String itemId, String reason, String by) {
        if (!Ids.ITEM_ID.matcher(itemId).matches() || !itemId.contains("/") || itemId.contains("#foundation")) {
            throw new GtaException(
                    ErrorCode.USAGE_UNKNOWN_ITEM,
                    "Not a scenario item id: " + itemId,
                    "Use an id like order.create/s01#component-01");
        }
        String scenarioId = itemId.substring(0, itemId.indexOf('#'));
        String flowId = scenarioId.substring(0, scenarioId.indexOf('/'));
        try (WorkLock ignored = WorkLock.acquire(paths.lock())) {
            Path shardPath = paths.oracleShard(flowId);
            Map<String, OracleShard.Entry> shard = OracleShard.read(shardPath);
            OracleShard.Entry e = shard.get(itemId);
            if (e == null || e.testClass == null) {
                throw new GtaException(
                        ErrorCode.USAGE_UNKNOWN_ITEM,
                        "No committed oracle for " + itemId,
                        "Only committed (passed) items can be relocked");
            }
            // the expected values come from the spec, never from the old lock: change the THEN first, then relock
            Spec.Scenario scenario = SpecParser.parse(paths.spec(flowId))
                    .scenario(scenarioId)
                    .orElseThrow(() -> new GtaException(
                            ErrorCode.USAGE_UNKNOWN_ITEM,
                            "Scenario " + scenarioId + " is not in " + paths.rel(paths.spec(flowId)),
                            "Relock only scenarios of the current spec"));
            AssertionScanner.ScenarioOracle oracle =
                    AssertionScanner.scan(paths.testSource(e.testClass), scenarioId, paths.testResources());
            List<String> then = scenario.thenLiterals();
            List<String> missing = AssertionScanner.missingThenLiterals(then, oracle.tokens());
            if (!scenario.reviewerChecked() && !missing.isEmpty()) {
                throw new GtaException(
                        ErrorCode.POLICY_ORACLE_MISMATCH,
                        "The test does not assert the spec's expected values " + missing,
                        "Make the assertions match the THEN clauses of " + scenarioId
                                + " (or change the spec first), then relock");
            }
            e.tokens = new ArrayList<>(then);
            e.fingerprint = OracleLock.fingerprint(oracle.assertionStatements().stream()
                    .filter(x -> e.tokens.stream().anyMatch(x::contains))
                    .toList());
            e.scenarioRevision = Planner.scenarioRevision(scenario);
            e.relockedBy = by;
            e.reason = reason;
            e.relockedAt = Instant.now().toString();
            OracleShard.write(shardPath, shard);
            return result(
                    "relocked " + itemId + " by " + by + " on " + e.tokens + "; commit " + paths.rel(shardPath)
                            + " so the PR shows it",
                    null);
        }
    }

    private ServiceProfile ensureProfile(boolean refresh) {
        if (!refresh && Files.exists(paths.profile())) {
            return Json.read(paths.profile(), ServiceProfile.class);
        }
        ServiceProfile p = Profiler.profile(paths.root(), registry, config.build.basePackage);
        Json.writeAtomic(paths.profile(), p);
        Json.writeAtomic(paths.flowCatalog(), p.entrypoints);
        return p;
    }

    private String resolveTarget(String target, ServiceProfile profile) {
        if (Ids.FLOW_ID.matcher(target).matches()) {
            return Ids.requireFlowId(target);
        }
        String t = target.replace('\\', '/');
        for (ServiceProfile.EntryPoint e : profile.entrypoints) {
            Optional<Ref> ref = Ref.parse(e.ref);
            if (ref.isEmpty()) {
                continue;
            }
            String simple = ref.get().simpleName();
            if (t.endsWith(ref.get().sourcePath()) || t.endsWith(simple + ".java") || t.equals(simple)) {
                return e.flowId;
            }
        }
        throw new GtaException(
                ErrorCode.USAGE_UNKNOWN_FLOW,
                "No flow found for " + target,
                "Use a flowId like order.create (see ./gtaw next without --target for candidates)");
    }

    // ------------------------------------------------------------------ delegated to the workflows

    public Map<String, Object> submitSpec(String flowId, String token) {
        return ctx.specs.submitSpec(flowId, token);
    }

    public Map<String, Object> check(String itemId, String token) {
        return ctx.items.check(itemId, token);
    }

    public Map<String, Object> submitItem(String itemId, String token) {
        return ctx.items.submitItem(itemId, token);
    }

    public Map<String, Object> submitReview(String itemId, String token, String verdict, String reason) {
        return ctx.items.submitReview(itemId, token, verdict, reason);
    }

    public Map<String, Object> verify(boolean newOnly) {
        return ctx.sessionEnd.verify(newOnly);
    }

    public Gate.Result gate(String base, List<Path> reportDirs) {
        return ctx.sessionEnd.gate(base, reportDirs);
    }

    public String report(String format) {
        return ctx.sessionEnd.report(format);
    }

    /** {@code gtaw run}: the seed tests (smoke) or every gta test, optionally one layer. */
    public RunResult runTests(boolean smoke, String layer) {
        return ctx.runs.runTests(smoke, layer);
    }
}
