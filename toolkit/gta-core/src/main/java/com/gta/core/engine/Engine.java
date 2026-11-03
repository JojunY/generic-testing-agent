package com.gta.core.engine;

import com.gta.core.brief.Briefs;
import com.gta.core.change.ChangeChecker;
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
import com.gta.core.profile.JavaIndex;
import com.gta.core.profile.Profiler;
import com.gta.core.profile.RefResolver;
import com.gta.core.profile.ServiceProfile;
import com.gta.core.registry.Registry;
import com.gta.core.report.Reporter;
import com.gta.core.run.FailureClassifier;
import com.gta.core.run.MavenRunner;
import com.gta.core.run.RunResult;
import com.gta.core.run.SurefireReports;
import com.gta.core.run.TestCase;
import com.gta.core.spec.AnswerParser;
import com.gta.core.spec.CommentMeta;
import com.gta.core.spec.Spec;
import com.gta.core.spec.SpecEditor;
import com.gta.core.spec.SpecLinter;
import com.gta.core.spec.SpecParser;
import com.gta.core.state.ItemStatus;
import com.gta.core.state.StateStore;
import com.gta.core.state.WorkState;
import com.gta.core.util.Git;
import com.gta.core.util.Hashes;
import com.gta.core.util.Ids;
import com.gta.core.util.Json;
import com.gta.core.util.Proc;
import com.gta.core.util.WorkLock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The deterministic blueprint (plan §4.2): {@code next → dispatch → check* → submit}. Every state change, test run,
 * classification, lock and commit happens here; agents only write specs/tests and give verdicts.
 */
public final class Engine {
    private static final Pattern EXPECTED_ACTUAL = Pattern.compile(
            "expected:\\s*<?\"?([^>\"\\n]+?)\"?>?\\s*but was:\\s*<?\"?([^>\"\\n]+?)\"?>?\\s*$", Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern ASSERTJ = Pattern.compile("expected:\\s*\"?(.+?)\"?\\s*\\n\\s*but was:\\s*\"?(.+?)\"?\\s*(\\n|$)");
    private static final List<String> TRIAGE_VERDICTS = List.of("test-defect", "oracle-defect", "product-defect", "spec-error",
            "spec-gap", "flaky", "environment", "expected-change");

    private final GtaPaths paths;
    private final GtaConfig config;
    private final Registry registry;
    private final StateStore store;
    private final Git git;
    private final Briefs briefs;

    public Engine(Path root) {
        this.paths = new GtaPaths(root.toAbsolutePath().normalize());
        this.config = GtaConfig.load(paths);
        this.registry = Registry.loadBundled();
        this.store = new StateStore(paths);
        this.git = new Git(paths.root());
        this.briefs = new Briefs(paths);
    }

    public GtaPaths paths() {
        return paths;
    }

    public GtaConfig config() {
        return config;
    }

    // ------------------------------------------------------------------ next

    public Map<String, Object> next(String mode, String target, String base, boolean takeover) {
        git.requireRepo();
        try (WorkLock ignored = WorkLock.acquire(paths.lock())) {
            WorkState st = store.load();
            Map<String, Object> recovered = touchSession(st, takeover);
            if (mode != null) {
                st.mode = mode;
            }
            ServiceProfile profile = ensureProfile(target != null);
            if (target != null) {
                st.activeFlow = resolveTarget(target, profile);
                st.pending = null;
            }
            Map<String, Object> out = step(st, profile, base);
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
        ServiceProfile.EntryPoint entry = profile.entrypoints.stream().filter(e -> e.flowId.equals(flowId)).findFirst().orElse(null);
        WorkState.SpecWork sw = st.specWork.computeIfAbsent(flowId, k -> new WorkState.SpecWork());
        String capability = Files.exists(specPath) ? SpecParser.parse(specPath).capability()
                : entry != null ? entry.capability : "rest-api";
        Registry.Capability cap = registry.capability(capability);
        boolean explainOnly = !cap.activeIn(Registry.CURRENT_PHASE);

        if (!Files.exists(specPath) || sw.needsRevision || "draft".equals(SpecParser.parse(specPath).status())) {
            return analyzeNode(st, sw, flowId, entry, capability, List.of());
        }
        Spec spec = SpecParser.parse(specPath);
        if ("approved".equals(spec.status())) {
            List<String> staleRefs = staleRefs(spec);
            if (!staleRefs.isEmpty()) {
                writeSpec(specPath, new SpecEditor(spec.text).setHeader("status", "stale").text());
                return analyzeNode(st, sw, flowId, entry, capability,
                        staleRefs.stream().map(r -> "ref changed since approval: " + r + " — re-check the affected scenarios").toList());
            }
        }
        if ("stale".equals(spec.status())) {
            return analyzeNode(st, sw, flowId, entry, capability, List.of("spec is stale: production code it references changed"));
        }
        if ("in-review".equals(spec.status())) {
            WorkState.Pending p = new WorkState.Pending();
            p.flowId = flowId;
            if (!spec.openQuestions().isEmpty()) {
                p.type = "answer";
                p.round = spec.questionRound == null ? spec.rev() : spec.questionRound;
                p.prompt = renderQuestions(spec, p.round);
            } else {
                p.type = "approve";
                p.rev = spec.rev();
                p.prompt = renderApproval(spec);
            }
            st.pending = p;
            return answerNode(p);
        }
        if (!"approved".equals(spec.status())) {
            return done("Flow " + flowId + " is " + spec.status() + "; nothing to do", null);
        }
        if (explainOnly) {
            return done("Capability " + capability + " is explain-only in " + Registry.CURRENT_PHASE
                    + " (active from " + cap.since + "). The approved spec is kept as documentation.", null);
        }
        if ("explain".equals(st.mode)) {
            st.mode = "flow"; // explain turns into flow mode once the human approved the spec (plan §6)
        }
        List<PlanItem> flowItems = syncPlan(st, spec, profile);
        return dispatch(st, spec, profile, flowItems, base);
    }

    private Map<String, Object> dispatch(WorkState st, Spec spec, ServiceProfile profile, List<PlanItem> items, String base) {
        if (st.consecutiveFailures >= config.budgets.maxConsecutiveFailures) {
            return node("escalate", Map.of("message", st.consecutiveFailures
                    + " items in a row needed a human; the run is paused. Resolve them (see ./gtaw status), then run /gta again."));
        }
        for (PlanItem item : items) {
            WorkState.ItemState s = st.items.get(item.id);
            switch (s.status) {
                case AUTHORING, REPAIRING -> {
                    st.seq++;
                    Path brief = briefs.author(st.seq, s, item, spec, profile);
                    return authorNode(item, s, brief);
                }
                case TRIAGING -> {
                    return reviewNode(st, s, item, spec, "triage");
                }
                case REVIEWING -> {
                    return reviewNode(st, s, item, spec, "oracle-review");
                }
                case NEEDS_HUMAN -> {
                    st.pending = humanDecision(s, item);
                    return answerNode(st.pending);
                }
                case VERIFYING -> {
                    // rebuilt after a lost work dir: verify the existing test first
                    return submitInternal(st, item, s, true);
                }
                default -> {
                    // terminal or pending: handled below
                }
            }
        }
        if (st.itemsThisSession >= config.budgets.maxItemsPerSession) {
            return finishSession(st, "session budget reached (" + st.itemsThisSession + " items); run /gta again to continue", base);
        }
        for (PlanItem item : items) {
            WorkState.ItemState s = st.items.get(item.id);
            if (s.status != ItemStatus.PENDING) {
                continue;
            }
            boolean depsOk = item.dependsOn.stream().allMatch(d -> st.items.containsKey(d) && st.items.get(d).status == ItemStatus.PASSED);
            if (!depsOk) {
                continue;
            }
            st.seq++;
            s.token = Briefs.token(st.session.id, st.seq);
            s.baseline = new ChangeChecker(paths).capture();
            st.move(s, ItemStatus.AUTHORING);
            Path brief = briefs.author(st.seq, s, item, spec, profile);
            return authorNode(item, s, brief);
        }
        boolean blocked = items.stream().anyMatch(i -> !st.items.get(i.id).status.terminal());
        return finishSession(st, blocked ? "remaining items are blocked by dependencies that did not pass" : "all items of " + spec.flowId() + " are done", base);
    }

    // ------------------------------------------------------------------ nodes

    private Map<String, Object> analyzeNode(WorkState st, WorkState.SpecWork sw, String flowId, ServiceProfile.EntryPoint entry,
                                            String capability, List<String> extraIssues) {
        st.seq++;
        sw.token = Briefs.token(st.session.id, st.seq);
        sw.baseline = new ChangeChecker(paths).capture();
        Path specPath = paths.spec(flowId);
        List<String> issues = new ArrayList<>(extraIssues);
        if (Files.exists(specPath)) {
            issues.addAll(SpecLinter.lint(SpecParser.parse(specPath), flowId, RefResolver.of(paths.root()), registry, paths.root()));
        }
        List<String> skills = new ArrayList<>(List.of("gta-mapping-flows", "gta-writing-behavior-specs"));
        if ("pr".equals(st.mode)) {
            skills.add("gta-analyzing-change-impact");
        }
        Path brief = briefs.analyze(st.seq, sw.token, flowId, entry, capability, skills, issues, sw.answersToApply, Files.exists(specPath));
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("flowId", flowId);
        extra.put("agent", "gta-analyst");
        extra.put("brief", paths.rel(brief));
        extra.put("token", sw.token);
        return node("analyze", extra);
    }

    private Map<String, Object> authorNode(PlanItem item, WorkState.ItemState s, Path brief) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("itemId", item.id);
        extra.put("agent", "gta-test-author");
        extra.put("brief", paths.rel(brief));
        extra.put("token", s.token);
        extra.put("status", s.status.id());
        return node("author", extra);
    }

    private Map<String, Object> reviewNode(WorkState st, WorkState.ItemState s, PlanItem item, Spec spec, String kind) {
        st.seq++;
        String details;
        if (kind.equals("oracle-review")) {
            details = "The test passed. Check that it asserts exactly what the THEN clauses say, on the right object, "
                    + "and that inputs really exercise the WHEN. Test file: `src/test/java/" + item.testClass.replace('.', '/') + ".java`."
                    + (s.structuralOracleChange ? "\n\n**Locked assertions were restructured (values unchanged): confirm the meaning is the same.**" : "")
                    + (s.inputsChangedAfterAssertion ? "\n\n**Inputs/fixtures changed after an assertion failure: make sure they were not bent to make the test pass.**" : "");
        } else {
            details = "The test failed with `" + (s.lastError == null ? "?" : s.lastError.code) + "`:\n\n```\n"
                    + (s.lastError == null || s.lastError.message == null ? "" : s.lastError.message) + "\n```\n\n"
                    + "Decide whether the test, the spec or the product is wrong. Never recommend changing the expected value to the actual value."
                    + (s.verdict != null && s.verdict.equals("flaky") ? "\n\ngtaw re-ran it and got different results: likely `flaky`." : "");
        }
        Path brief = briefs.review(st.seq, s, item, spec, kind, details);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("itemId", item.id);
        extra.put("agent", "gta-reviewer");
        extra.put("kind", kind);
        extra.put("brief", paths.rel(brief));
        return node("review", extra);
    }

    private Map<String, Object> answerNode(WorkState.Pending p) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("type", p.type);
        if (p.flowId != null) {
            extra.put("flowId", p.flowId);
        }
        if (p.itemId != null) {
            extra.put("itemId", p.itemId);
        }
        extra.put("prompt", p.prompt);
        extra.put("instruction", "Show the prompt to the human verbatim, then pass their reply verbatim to ./gtaw answer \"<reply>\"");
        return node("answer", extra);
    }

    private Map<String, Object> chooseFlow(ServiceProfile profile) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        profile.entrypoints.stream().sorted(Comparator.comparingInt((ServiceProfile.EntryPoint e) -> -e.risk)).limit(8).forEach(e -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("flowId", e.flowId);
            c.put("risk", e.risk);
            c.put("entry", e.ref + (e.httpMethod != null ? " (" + e.httpMethod + " " + e.path + ")" : ""));
            c.put("spec", Files.exists(paths.spec(e.flowId)) ? SpecParser.parse(paths.spec(e.flowId)).status() : "none");
            candidates.add(c);
        });
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("message", "No flow selected. Ask the human which flow to work on, then run ./gtaw next --target <flowId>");
        extra.put("candidates", candidates);
        return node("done", extra);
    }

    private static Map<String, Object> node(String node, Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", node);
        out.putAll(extra);
        return out;
    }

    private static Map<String, Object> done(String message, Map<String, Object> more) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("message", message);
        if (more != null) {
            extra.putAll(more);
        }
        return node("done", extra);
    }

    // ------------------------------------------------------------------ human decisions

    private String renderQuestions(Spec spec, String round) {
        StringBuilder b = new StringBuilder();
        b.append("── ").append(spec.flowId()).append(" ").append(round).append(" questions (gtaw) ──\n");
        b.append("Spec: ").append(paths.rel(spec.path)).append("\n");
        for (Spec.Question q : spec.openQuestions()) {
            b.append(q.number).append(". ").append("high".equals(q.impact()) ? "[high] " : "").append(q.text.replaceFirst("^\\[[^]]*]\\s*", "")).append('\n');
            for (Spec.Option o : q.options) {
                b.append("     ").append(o.letter).append(") ").append(o.text).append('\n');
            }
        }
        if (!spec.findings.isEmpty()) {
            b.append("Findings:\n");
            spec.findings.forEach(f -> b.append("  - ").append(f.meta.getOr("kind", "finding")).append(": ").append(f.text).append('\n'));
        }
        b.append("Reply like \"").append(round).append(": 1A 2B 其余默认\". High-impact questions need an explicit answer; ")
                .append("\"其余默认\" picks the \"= 代码现状\" option and only produces characterization tests.");
        return b.toString();
    }

    private String renderApproval(Spec spec) {
        long confirmed = spec.scenarios().stream().filter(s -> s.source().equals("confirmed") || s.source().equals("documented")).count();
        long observed = spec.scenarios().stream().filter(s -> s.source().equals("observed")).count();
        long defaulted = spec.scenarios().stream().filter(s -> s.source().equals("defaulted")).count();
        StringBuilder b = new StringBuilder();
        b.append("── ").append(spec.flowId()).append("@").append(spec.rev()).append(" ready for approval ──\n");
        b.append("Spec: ").append(paths.rel(spec.path)).append(" — ").append(spec.requirements.size()).append(" requirements, ")
                .append(spec.scenarios().size()).append(" scenarios (confirmed/documented ").append(confirmed).append(", observed ")
                .append(observed).append(", defaulted ").append(defaulted).append(")\n");
        b.append("Observed and defaulted scenarios become characterization tests (they lock current behaviour, not correctness).\n");
        b.append("Reply \"批准 ").append(spec.flowId()).append("@").append(spec.rev()).append("\" (or \"approve ")
                .append(spec.flowId()).append("@").append(spec.rev()).append("\") to approve, or say what to change.");
        return b.toString();
    }

    private WorkState.Pending humanDecision(WorkState.ItemState s, PlanItem item) {
        WorkState.Pending p = new WorkState.Pending();
        p.itemId = item.id;
        p.flowId = item.flowId;
        boolean defect = s.reason != null && s.reason.startsWith("defect:");
        p.type = defect ? "defect" : "escalation";
        StringBuilder b = new StringBuilder();
        b.append("── ").append(item.id).append(defect ? " looks like a product defect" : " needs a human").append(" ──\n");
        b.append(s.reason == null ? "" : s.reason.replaceFirst("^defect:\\s*", "")).append('\n');
        if (s.lastError != null && s.lastError.message != null) {
            b.append("Last failure (").append(s.lastError.code).append("): ").append(firstLines(s.lastError.message, 6)).append('\n');
        }
        b.append("Escalation file: ").append(".gta/escalations/").append(Ids.itemKey(item.id)).append(".md\n");
        if (defect) {
            b.append("  A) Confirm it is a defect (pin it with @KnownDefect and draft an issue)\n");
            b.append("  B) The spec is wrong (back to review)\n");
            b.append("  C) Retry\n");
        } else {
            b.append("  A) Retry (after you fixed the cause)\n");
            b.append("  B) Cancel this item\n");
        }
        b.append("Reply with the letter.");
        p.prompt = b.toString();
        return p;
    }

    public Map<String, Object> answer(String text) {
        try (WorkLock ignored = WorkLock.acquire(paths.lock())) {
            WorkState st = store.load();
            touch(st);
            if (st.pending == null) {
                throw new GtaException(ErrorCode.USAGE_UNPARSEABLE_ANSWER, "Nothing is waiting for an answer",
                        "Run ./gtaw next; answers are only accepted while an 'answer' node is open");
            }
            AnswerParser.Parsed parsed = AnswerParser.parse(text);
            WorkState.Pending p = st.pending;
            Map<String, Object> out = switch (p.type) {
                case "answer" -> applyAnswers(st, p, parsed, text);
                case "approve" -> applyApproval(st, p, parsed, text);
                case "defect", "escalation" -> applyDecision(st, p, parsed, text);
                default -> throw new GtaException(ErrorCode.INTERNAL_ERROR, "Unknown pending type " + p.type, "Run ./gtaw abort");
            };
            store.save(st);
            return out;
        }
    }

    private Map<String, Object> applyAnswers(WorkState st, WorkState.Pending p, AnswerParser.Parsed parsed, String text) {
        if (!(parsed instanceof AnswerParser.Answers a)) {
            throw new GtaException(ErrorCode.USAGE_UNPARSEABLE_ANSWER, "Expected answers to " + p.round,
                    "Reply like '" + p.round + ": 1A 2B 其余默认'");
        }
        if (a.round() != null && !a.round().equals(p.round)) {
            throw new GtaException(ErrorCode.USAGE_UNPARSEABLE_ANSWER, "These answers are for " + a.round() + " but the open round is " + p.round,
                    "Answer the questions of " + p.round);
        }
        Path specPath = paths.spec(p.flowId);
        Spec spec = SpecParser.parse(specPath);
        SpecEditor ed = new SpecEditor(spec.text);
        CommentMeta record = new CommentMeta("answer", List.of(new CommentMeta.Entry("round", p.round),
                new CommentMeta.Entry("channel", "chat")));
        List<String> unansweredHigh = new ArrayList<>();
        List<String> toApply = new ArrayList<>();
        for (Spec.Question q : spec.openQuestions()) {
            String letter = a.picks().get(q.number);
            String source = "confirmed";
            if (letter == null && a.restDefault()) {
                if ("high".equals(q.impact())) {
                    unansweredHigh.add(q.number);
                    continue;
                }
                letter = q.currentCodeOption().map(o -> o.letter).orElse(null);
                source = "defaulted";
            }
            if (letter == null) {
                if ("high".equals(q.impact())) {
                    unansweredHigh.add(q.number);
                }
                continue;
            }
            String chosen = letter;
            Spec.Option option = q.options.stream().filter(o -> o.letter.equals(chosen)).findFirst()
                    .orElseThrow(() -> new GtaException(ErrorCode.USAGE_UNPARSEABLE_ANSWER, "Question " + q.number + " has no option " + chosen,
                            "Pick one of the listed letters"));
            ed.tick(q.id(), letter);
            record = record.with(q.id().substring(q.id().indexOf('/') + 1), "defaulted".equals(source) ? letter + "(default)" : letter);
            for (String target : q.affects()) {
                for (Spec.Scenario s : spec.scenarios()) {
                    boolean hit = target.equals(s.id()) || spec.requirementOf(s).map(r -> target.equals(r.id())).orElse(false);
                    if (hit) {
                        ed.setElement(s.id(), "source", source).setElement(s.id(), "by", p.round);
                    }
                }
                spec.invariants.stream().filter(i -> target.equals(i.id())).forEach(i -> ed.setElement(i.id(), "source", "confirmed"));
            }
            if (!option.isCurrentCode()) {
                toApply.add("Q" + q.number + " (" + q.id() + ") → " + letter + ") " + option.text);
            }
        }
        record = record.with("text", text);
        ed.appendRecord(record);
        writeSpec(specPath, ed.text());
        snapshot(p.flowId);
        st.newConfirmations.add(p.flowId + " " + p.round + ": \"" + text + "\"");
        Spec after = SpecParser.parse(specPath);
        if (!after.openQuestions().isEmpty()) {
            p.prompt = renderQuestions(after, p.round);
            return result("answers recorded; still open: " + after.openQuestions().stream().map(q -> q.number).toList()
                    + (unansweredHigh.isEmpty() ? "" : " (high impact needs an explicit answer)"), Map.of("prompt", p.prompt));
        }
        WorkState.SpecWork sw = st.specWork.computeIfAbsent(p.flowId, k -> new WorkState.SpecWork());
        if (!toApply.isEmpty()) {
            sw.needsRevision = true;
            sw.answersToApply = toApply;
            st.pending = null;
            return result("answers recorded; the analyst will now rewrite the affected THEN clauses (run ./gtaw next)", null);
        }
        String newRev = bump(after.rev());
        writeSpec(specPath, new SpecEditor(after.text).setHeader("rev", newRev).text());
        snapshot(p.flowId);
        Spec revised = SpecParser.parse(specPath);
        p.type = "approve";
        p.rev = newRev;
        p.prompt = renderApproval(revised);
        return result("answers recorded; spec is now " + p.flowId + "@" + newRev, Map.of("prompt", p.prompt));
    }

    private Map<String, Object> applyApproval(WorkState st, WorkState.Pending p, AnswerParser.Parsed parsed, String text) {
        if (!(parsed instanceof AnswerParser.Approval a) || !a.flowId().equals(p.flowId) || !a.rev().equals(p.rev)) {
            throw new GtaException(ErrorCode.USAGE_UNPARSEABLE_ANSWER, "Approval must name the current revision",
                    "Reply exactly '批准 " + p.flowId + "@" + p.rev + "' (or 'approve " + p.flowId + "@" + p.rev + "')");
        }
        Path specPath = paths.spec(p.flowId);
        Spec spec = SpecParser.parse(specPath);
        if (!spec.openQuestions().isEmpty()) {
            throw new GtaException(ErrorCode.USAGE_UNPARSEABLE_ANSWER, "There are open questions", "Answer them first");
        }
        StringBuilder hashes = new StringBuilder();
        for (Spec.Requirement r : spec.requirements) {
            if (hashes.length() > 0) {
                hashes.append(',');
            }
            hashes.append(r.id().substring(r.id().indexOf('/') + 1)).append(':').append(requirementHash(spec, r));
        }
        CommentMeta approval = new CommentMeta("approval", List.of(new CommentMeta.Entry("rev", p.rev),
                new CommentMeta.Entry("channel", "chat"), new CommentMeta.Entry("hashes", hashes.toString()),
                new CommentMeta.Entry("text", text)));
        String updated = new SpecEditor(spec.text).setHeader("status", "approved").appendRecord(approval).text();
        writeSpec(specPath, updated);
        snapshot(p.flowId);
        git.commit(List.of(paths.rel(specPath)), "docs(spec): " + p.flowId + " " + p.rev + " approved\n\nGta-Approval: " + text);
        st.newConfirmations.add(p.flowId + "@" + p.rev + " approved: \"" + text + "\"");
        st.pending = null;
        return result(p.flowId + "@" + p.rev + " approved and committed; run ./gtaw next to start writing tests", null);
    }

    private Map<String, Object> applyDecision(WorkState st, WorkState.Pending p, AnswerParser.Parsed parsed, String text) {
        if (!(parsed instanceof AnswerParser.Choice c)) {
            throw new GtaException(ErrorCode.USAGE_UNPARSEABLE_ANSWER, "Expected a single option letter", "Reply A, B or C");
        }
        WorkState.ItemState s = st.item(p.itemId);
        PlanItem item = planItem(p.itemId);
        String letter = c.letter();
        String msg;
        if ("defect".equals(p.type) && letter.equals("A")) {
            s.intentOverride = "known-defect";
            s.reason = "confirmed product defect: pin with @KnownDefect";
            st.move(s, ItemStatus.PENDING);
            writeEscalation(item, s, "Confirmed as a product defect by a human: \"" + text + "\". Issue draft: the behaviour of "
                    + item.ref + " violates the approved spec.");
            msg = "defect confirmed; the test will be pinned with @KnownDefect";
        } else if ("defect".equals(p.type) && letter.equals("B")) {
            st.move(s, ItemStatus.CANCELLED);
            WorkState.SpecWork sw = st.specWork.computeIfAbsent(item.flowId, k -> new WorkState.SpecWork());
            sw.needsRevision = true;
            sw.answersToApply = List.of("Human says the expectation of " + item.ref + " is wrong: revise it and add an open question if unsure");
            Path specPath = paths.spec(item.flowId);
            writeSpec(specPath, new SpecEditor(SpecParser.parse(specPath).text).setHeader("status", "in-review").text());
            snapshot(item.flowId);
            msg = "spec sent back for revision";
        } else if (("defect".equals(p.type) && letter.equals("C")) || ("escalation".equals(p.type) && letter.equals("A"))) {
            st.move(s, ItemStatus.PENDING);
            s.attempts = 0;
            s.lastError = null;
            st.consecutiveFailures = 0;
            msg = "item reset; it will be retried";
        } else if ("escalation".equals(p.type) && letter.equals("B")) {
            st.move(s, ItemStatus.CANCELLED);
            msg = "item cancelled";
        } else {
            throw new GtaException(ErrorCode.USAGE_UNPARSEABLE_ANSWER, "Option " + letter + " is not available", "Pick one of the listed letters");
        }
        st.newConfirmations.add(p.itemId + ": \"" + text + "\" (" + msg + ")");
        st.pending = null;
        return result(msg, null);
    }

    // ------------------------------------------------------------------ spec submission

    public Map<String, Object> submitSpec(String flowId, String token) {
        try (WorkLock ignored = WorkLock.acquire(paths.lock())) {
            WorkState st = store.load();
            touch(st);
            WorkState.SpecWork sw = st.specWork.get(flowId);
            if (sw == null || sw.token == null || !sw.token.equals(token)) {
                throw new GtaException(ErrorCode.STATE_STALE_SESSION, "Token does not match the open analyze node of " + flowId,
                        "Run ./gtaw next to get a fresh brief and token");
            }
            Path specPath = paths.spec(flowId);
            String specRel = paths.rel(specPath);
            ChangeChecker.Result scope = new ChangeChecker(paths).check(sw.baseline,
                    List.of(specRel, ".gta/specs/service-overview.md"), paths.recover().resolve("analyze-" + st.seq), true);
            if (!scope.ok()) {
                sw.attempts++;
                store.save(st);
                throw new GtaException(ErrorCode.POLICY_OUT_OF_SCOPE_CHANGE, "The analyst may only change " + specRel,
                        "Reverted (backup in .gta/work/recover/): " + scope.restored()).detail("outOfScope", scope.outOfScope());
            }
            if (!Files.exists(specPath)) {
                throw new GtaException(ErrorCode.USAGE_UNKNOWN_FLOW, "Spec not found: " + specRel, "Write the spec file first");
            }
            Spec spec = SpecParser.parse(specPath);
            List<String> owned = ownedFieldViolations(flowId, spec);
            if (!owned.isEmpty()) {
                sw.attempts++;
                store.save(st);
                throw new GtaException(ErrorCode.POLICY_GTA_OWNED_FIELD, "Fields owned by gtaw were changed: " + owned,
                        "Revert them (status, rev, ticks, source=confirmed/defaulted, the Answers section) and submit again");
            }
            RefResolver refs = RefResolver.of(paths.root());
            SpecEditor ed = new SpecEditor(spec.text);
            List<String> notes = new ArrayList<>();
            for (Spec.Scenario s : spec.scenarios()) {
                if ("documented".equals(s.source())) {
                    List<String> docIssues = new ArrayList<>();
                    SpecLinter.lint(SpecParser.parseText(spec.text), flowId, null, registry, paths.root()).stream()
                            .filter(i -> i.contains(s.title) && i.contains("documented") || i.contains(s.title) && i.contains("quote"))
                            .forEach(docIssues::add);
                    if (!docIssues.isEmpty()) {
                        ed.setElement(s.id(), "source", "observed");
                        notes.add(s.id() + " downgraded to observed (citation not verifiable)");
                    }
                }
            }
            for (Spec.Requirement r : spec.requirements) {
                Map<String, String> hashes = new LinkedHashMap<>();
                for (String ref : r.refs()) {
                    refs.hash(ref).ifPresent(h -> hashes.put(ref, h));
                }
                if (r.id() != null && !hashes.isEmpty()) {
                    ed.setRefHashes(r.id(), hashes);
                }
            }
            Spec edited = SpecParser.parseText(ed.text());
            edited.path = specPath;
            List<String> issues = SpecLinter.lint(edited, flowId, refs, registry, paths.root());
            writeSpec(specPath, ed.text());
            if (!issues.isEmpty()) {
                sw.attempts++;
                store.save(st);
                throw new GtaException(ErrorCode.POLICY_SPEC_INVALID, issues.size() + " lint issue(s) in " + specRel,
                        "Fix them and run ./gtaw submit --spec " + flowId + " --token " + token + " again").detail("issues", issues);
            }
            String status = edited.status();
            String rev = edited.rev();
            if ("draft".equals(status) || "stale".equals(status) || sw.needsRevision) {
                status = "in-review";
                rev = bump(rev);
            } else if ("approved".equals(status) && !approvalStillValid(edited)) {
                status = "in-review";
                rev = bump(rev);
            }
            SpecEditor finalEd = new SpecEditor(ed.text()).setHeader("status", status).setHeader("rev", rev);
            if (!edited.openQuestions().isEmpty()) {
                finalEd.setRound(rev);
            }
            writeSpec(specPath, finalEd.text());
            snapshot(flowId);
            sw.needsRevision = false;
            sw.answersToApply = new ArrayList<>();
            sw.attempts = 0;
            sw.token = null;
            st.pending = null;
            store.save(st);
            Spec done = SpecParser.parse(specPath);
            Map<String, Object> more = new LinkedHashMap<>();
            more.put("status", status);
            more.put("rev", rev);
            more.put("openQuestions", done.openQuestions().size());
            if (!notes.isEmpty()) {
                more.put("notes", notes);
            }
            return result("spec accepted: " + flowId + "@" + rev + " (" + status + ")", more);
        }
    }

    // ------------------------------------------------------------------ items

    public Map<String, Object> check(String itemId, String token) {
        try (WorkLock ignored = WorkLock.acquire(paths.lock())) {
            WorkState st = store.load();
            touch(st);
            WorkState.ItemState s = requireToken(st, itemId, token, ItemStatus.AUTHORING, ItemStatus.REPAIRING);
            if (s.checks >= config.budgets.maxChecksPerItem) {
                throw new GtaException(ErrorCode.STATE_NOTHING_PENDING, "Check budget used (" + s.checks + ")",
                        "Submit with ./gtaw submit --item " + itemId + " --token " + token);
            }
            s.checks++;
            PlanItem item = planItem(itemId);
            String runId = Ids.newRunId(git.head());
            RunResult run = runItem(runId, item, false, false);
            AssertionScanner.ScenarioOracle oracle = scanOracle(item);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("runId", runId);
            out.put("checksLeft", config.budgets.maxChecksPerItem - s.checks);
            out.putAll(summarize(run));
            if (!item.isFoundation() && !item.oracle.reviewerChecked && oracle.found()) {
                out.put("missingExpectedValues", AssertionScanner.missingThenLiterals(item.oracle.then, oracle.tokens()));
            }
            if (s.lock == null && reached(run) && !item.isFoundation()) {
                s.lock = OracleLock.create(oracle, item.oracle.then, runId);
                out.put("oracleLocked", s.lock.tokens);
            } else if (s.lock != null) {
                OracleLock.Check c = OracleLock.check(s.lock, oracle);
                out.put("oracleLock", c.verdict().name().toLowerCase());
                if (!c.missingTokens().isEmpty()) {
                    out.put("lockedValuesMissing", c.missingTokens());
                }
            }
            store.save(st);
            return out;
        }
    }

    public Map<String, Object> submitItem(String itemId, String token) {
        try (WorkLock ignored = WorkLock.acquire(paths.lock())) {
            WorkState st = store.load();
            touch(st);
            WorkState.ItemState s = requireToken(st, itemId, token, ItemStatus.AUTHORING, ItemStatus.REPAIRING);
            Map<String, Object> out = submitInternal(st, planItem(itemId), s, false);
            store.save(st);
            return out;
        }
    }

    private Map<String, Object> submitInternal(WorkState st, PlanItem item, WorkState.ItemState s, boolean rebuilt) {
        ItemStatus from = s.status;
        if (!rebuilt) {
            ChangeChecker.Result scope = new ChangeChecker(paths).check(s.baseline, item.ownsFiles,
                    paths.recover().resolve(Ids.itemKey(item.id) + "-" + st.seq), true);
            st.move(s, ItemStatus.VERIFYING);
            if (!scope.ok()) {
                return fail(st, item, s, ErrorCode.POLICY_OUT_OF_SCOPE_CHANGE,
                        "Changed files outside ownsFiles were reverted: " + scope.restored()
                                + (scope.unrestorable().isEmpty() ? "" : "; not restorable (pre-existing changes): " + scope.unrestorable()));
            }
        }
        String runId = Ids.newRunId(git.head());
        RunResult run = runItem(runId, item, false, false);
        s.evidenceRunId = runId;
        if (run.compileError) {
            return fail(st, item, s, ErrorCode.BUILD_COMPILE_ERROR, run.compileMessage);
        }
        if (run.cases.isEmpty()) {
            return fail(st, item, s, ErrorCode.TEST_NOT_FOUND, "No test ran for " + item.testClass
                    + (item.isFoundation() ? "" : " with @Tag(\"scenario:" + item.ref + "\")"));
        }
        AssertionScanner.ScenarioOracle oracle = scanOracle(item);
        if (!item.isFoundation()) {
            if (s.lock == null && reached(run)) {
                s.lock = OracleLock.create(oracle, item.oracle.then, runId);
            }
            if (!item.oracle.reviewerChecked) {
                List<String> missing = AssertionScanner.missingThenLiterals(item.oracle.then, oracle.tokens());
                if (!missing.isEmpty()) {
                    return fail(st, item, s, ErrorCode.POLICY_ORACLE_MISMATCH,
                            "Expected values from the spec are not asserted: " + missing);
                }
            }
            OracleLock.Check lockCheck = OracleLock.check(s.lock, oracle);
            if (lockCheck.verdict() == OracleLock.Verdict.MODIFIED) {
                return fail(st, item, s, ErrorCode.POLICY_ORACLE_MODIFIED,
                        "Locked expected values were changed or removed: " + lockCheck.missingTokens() + ". Restore them; only a human can relock.");
            }
            s.structuralOracleChange = lockCheck.verdict() == OracleLock.Verdict.STRUCTURAL_CHANGE;
            if (s.lock != null && s.lastError != null && ErrorCode.valueOf(s.lastError.code).needsTriage()
                    && !s.lock.watched.equals(oracle.watchedHash())) {
                s.inputsChangedAfterAssertion = true;
            }
        }
        List<TestCase> failures = run.failures();
        if ("known-defect".equals(s.intentOverride) && !run.cases.isEmpty() && run.cases.stream().allMatch(c -> "known-defect".equals(c.outcome))) {
            st.move(s, ItemStatus.DEFECT_FOUND);
            commitItem(st, item, s, runId, "pin known defect");
            st.itemsThisSession++;
            return result("defect-found: " + item.id + " pinned with @KnownDefect (build stays green)", Map.of("runId", runId));
        }
        if (failures.isEmpty()) {
            if ("reproducing".equals(item.intent)) {
                return fail(st, item, s, ErrorCode.TEST_ASSERTION_FAILED, "A reproducing test must fail for the reported reason, but it passed");
            }
            if (config.qualityGates.oracleReview && !item.isFoundation()) {
                st.move(s, ItemStatus.REVIEWING);
                return result("passed run " + runId + "; waiting for the reviewer's oracle review", Map.of("runId", runId));
            }
            return markPassed(st, item, s, runId);
        }
        TestCase first = failures.get(0);
        ErrorCode code = ErrorCode.valueOf(first.code);
        String message = (first.failureType == null ? "" : first.failureType + ": ") + (first.message == null ? "" : first.message);
        if (code.isMechanical()) {
            return fail(st, item, s, code, message + "\n" + firstLines(first.stack, 8));
        }
        // assertion / SUT error / timeout: re-run twice before triage (plan §5.3)
        int consistent = 0;
        for (int i = 0; i < 2; i++) {
            RunResult again = runItem(Ids.newRunId(git.head()), item, false, false);
            if (!again.failures().isEmpty()) {
                consistent++;
            }
        }
        s.lastError = lastError(s, code, message);
        s.lastError.defectFingerprint = FailureClassifier.knownDefectFingerprint(first.failureType, first.message);
        s.verdict = consistent < 2 ? "flaky" : null;
        if ("reproducing".equals(item.intent) && consistent == 2 && code == ErrorCode.TEST_ASSERTION_FAILED) {
            s.reason = "defect: reproducing test fails for the reported reason";
            return needsHuman(st, item, s, s.reason);
        }
        st.move(s, ItemStatus.TRIAGING);
        return result(code.name() + " (" + (consistent == 2 ? "reproducible" : "flaky: " + consistent + "/2 re-runs failed")
                + "); waiting for the reviewer's triage", Map.of("runId", runId, "code", code.name()));
    }

    public Map<String, Object> submitReview(String itemId, String verdict, String reason) {
        try (WorkLock ignored = WorkLock.acquire(paths.lock())) {
            WorkState st = store.load();
            touch(st);
            WorkState.ItemState s = st.item(itemId);
            PlanItem item = planItem(itemId);
            Map<String, Object> out;
            if (s.status == ItemStatus.REVIEWING) {
                if ("accept".equals(verdict)) {
                    out = markPassed(st, item, s, s.evidenceRunId);
                } else if ("reject".equals(verdict)) {
                    st.move(s, ItemStatus.VERIFYING);
                    out = fail(st, item, s, ErrorCode.TEST_CODE_ERROR, "Reviewer rejected the oracle: " + reason);
                } else {
                    throw new GtaException(ErrorCode.USAGE_INVALID_ARGUMENT, "Oracle review verdict must be accept or reject", "Use --verdict accept|reject");
                }
            } else if (s.status == ItemStatus.TRIAGING) {
                if (!TRIAGE_VERDICTS.contains(verdict)) {
                    throw new GtaException(ErrorCode.USAGE_INVALID_ARGUMENT, "Unknown triage verdict " + verdict, "Use one of " + TRIAGE_VERDICTS);
                }
                out = applyTriage(st, item, s, verdict, reason);
            } else {
                throw new GtaException(ErrorCode.STATE_ILLEGAL_TRANSITION, itemId + " is " + s.status.id() + ", not under review",
                        "Run ./gtaw next");
            }
            store.save(st);
            return out;
        }
    }

    private Map<String, Object> applyTriage(WorkState st, PlanItem item, WorkState.ItemState s, String verdict, String reason) {
        boolean humanBacked = "confirmed".equals(item.oracle.source) || "documented".equals(item.oracle.source);
        s.verdict = verdict;
        switch (verdict) {
            case "test-defect", "flaky" -> {
                st.move(s, ItemStatus.REPAIRING);
                s.attempts++;
                s.reason = ("flaky".equals(verdict) ? "flaky: only waiting/synchronisation may change. " : "test defect: ") + reason;
                if (s.attempts >= config.budgets.maxAttemptsPerItem) {
                    return needsHuman(st, item, s, "repair attempts exhausted after triage: " + reason);
                }
                return result(item.id + " back to the author (" + verdict + "); oracle stays locked", null);
            }
            case "product-defect" -> {
                if (!humanBacked) {
                    throw new GtaException(ErrorCode.USAGE_INVALID_ARGUMENT,
                            "The oracle of " + item.id + " is " + item.oracle.source + "; it cannot prove a product defect",
                            "Use spec-error (observation was wrong) or test-defect");
                }
                return needsHuman(st, item, s, "defect: " + reason);
            }
            case "spec-error" -> {
                if (!humanBacked) {
                    return autoCorrect(st, item, s, reason);
                }
                return needsHuman(st, item, s, "spec-error on a " + item.oracle.source + " expectation: " + reason);
            }
            default -> {
                return needsHuman(st, item, s, verdict + ": " + reason);
            }
        }
    }

    /** Observed/defaulted expectation proved wrong by the code: correct the THEN from the actual value (plan §3.2 rule 1). */
    private Map<String, Object> autoCorrect(WorkState st, PlanItem item, WorkState.ItemState s, String reason) {
        String msg = s.lastError == null ? "" : s.lastError.message;
        Matcher m = EXPECTED_ACTUAL.matcher(msg == null ? "" : msg);
        Matcher a = ASSERTJ.matcher(msg == null ? "" : msg);
        String expected = null;
        String actual = null;
        if (m.find()) {
            expected = m.group(1).trim();
            actual = m.group(2).trim();
        } else if (a.find()) {
            expected = a.group(1).trim();
            actual = a.group(2).trim();
        }
        if (expected == null || !item.oracle.then.contains(expected)) {
            return needsHuman(st, item, s, "spec-error on an observed expectation, but the actual value could not be read from the failure: " + reason);
        }
        Path specPath = paths.spec(item.flowId);
        Spec spec = SpecParser.parse(specPath);
        String newRev = bump(spec.rev());
        writeSpec(specPath, new SpecEditor(spec.text).replaceThenLiteral(item.ref, expected, actual).setHeader("rev", newRev).text());
        snapshot(item.flowId);
        List<PlanItem> plan = store.loadPlan();
        for (PlanItem p : plan) {
            if (p.id.equals(item.id)) {
                p.oracle.then = new ArrayList<>(p.oracle.then);
                int idx = p.oracle.then.indexOf(expected);
                if (idx >= 0) {
                    p.oracle.then.set(idx, actual);
                }
                Spec revised = SpecParser.parse(specPath);
                revised.scenario(p.ref).ifPresent(sc -> p.scenarioRevision = Planner.scenarioRevision(sc));
            }
        }
        store.savePlan(plan);
        if (s.lock != null) {
            String exp = expected;
            s.lock.tokens.replaceAll(t -> t.equals(exp) ? null : t);
            s.lock.tokens.removeIf(t -> t == null);
            s.lock.tokens.add(actual);
            s.lock.statements.removeIf(stmt -> stmt.contains(exp));
        }
        st.move(s, ItemStatus.REPAIRING);
        s.reason = "spec corrected from the actual behaviour: `" + expected + "` → `" + actual + "`; update the assertion to the new expected value";
        st.pendingReview.add(item.ref + ": `" + expected + "` → `" + actual + "` (" + reason + ")");
        return result(item.id + ": observed THEN corrected to `" + actual + "` (" + item.flowId + "@" + newRev
                + "); listed for re-confirmation; back to the author", null);
    }

    private Map<String, Object> fail(WorkState st, PlanItem item, WorkState.ItemState s, ErrorCode code, String message) {
        s.lastError = lastError(s, code, message);
        s.attempts++;
        if (s.lastError.repeats >= 2) {
            return needsHuman(st, item, s, "the same failure happened twice in a row: " + code.name());
        }
        if (s.attempts >= config.budgets.maxAttemptsPerItem) {
            return needsHuman(st, item, s, "repair attempts exhausted (" + s.attempts + "): " + code.name());
        }
        st.move(s, ItemStatus.REPAIRING);
        Map<String, Object> more = new LinkedHashMap<>();
        more.put("code", code.name());
        more.put("attemptsLeft", config.budgets.maxAttemptsPerItem - s.attempts);
        more.put("detail", firstLines(message, 12));
        return result(code.name() + ": fix it, check, then submit again", more);
    }

    private WorkState.LastError lastError(WorkState.ItemState s, ErrorCode code, String message) {
        WorkState.LastError e = new WorkState.LastError();
        e.code = code.name();
        e.message = message;
        e.fingerprint = FailureClassifier.fingerprint(code.name(), message);
        e.repeats = s.lastError != null && e.fingerprint.equals(s.lastError.fingerprint) ? s.lastError.repeats + 1 : 1;
        return e;
    }

    private Map<String, Object> needsHuman(WorkState st, PlanItem item, WorkState.ItemState s, String reason) {
        s.reason = reason;
        if (s.status != ItemStatus.NEEDS_HUMAN) {
            st.move(s, ItemStatus.NEEDS_HUMAN);
        }
        st.consecutiveFailures++;
        st.itemsThisSession++;
        writeEscalation(item, s, reason);
        return result("needs-human: " + item.id + " — " + reason, null);
    }

    private Map<String, Object> markPassed(WorkState st, PlanItem item, WorkState.ItemState s, String runId) {
        st.move(s, ItemStatus.PASSED);
        if ("known-defect".equals(s.intentOverride)) {
            s.intentOverride = null;
        }
        commitItem(st, item, s, runId, item.title);
        st.sessionPassed.add(item.id);
        st.itemsThisSession++;
        st.consecutiveFailures = 0;
        return result("passed: " + item.id + " (run " + runId + ", commit " + (s.commit == null ? "-" : s.commit.substring(0, 7)) + ")", null);
    }

    private void commitItem(WorkState st, PlanItem item, WorkState.ItemState s, String runId, String summary) {
        List<String> files = new ArrayList<>();
        for (String changed : new ChangeChecker(paths).changedSince(s.baseline == null ? new ChangeChecker(paths).capture() : s.baseline)) {
            if (ChangeChecker.matchesAny(changed, item.ownsFiles)) {
                files.add(changed);
            }
        }
        if (!item.isFoundation() && s.lock != null) {
            Path shardPath = paths.oracleShard(item.flowId);
            Map<String, OracleShard.Entry> shard = OracleShard.read(shardPath);
            OracleShard.Entry e = new OracleShard.Entry();
            e.scenarioRevision = item.scenarioRevision;
            e.fingerprint = OracleLock.fingerprint(scanOracle(item).assertionStatements().stream()
                    .filter(x -> s.lock.tokens.stream().anyMatch(x::contains)).toList());
            e.tokens = s.lock.tokens;
            e.testClass = item.testClass;
            e.intent = item.intent;
            e.source = item.oracle.source;
            shard.put(item.id, e);
            OracleShard.write(shardPath, shard);
            files.add(paths.rel(shardPath));
        }
        if (files.isEmpty()) {
            return;
        }
        String msg = "test(" + item.domain() + "): " + summary + "\n\nGta-Item: " + item.id + "\nGta-Run: " + runId;
        git.commit(files, msg).ifPresent(sha -> s.commit = sha);
    }

    private void writeEscalation(PlanItem item, WorkState.ItemState s, String reason) {
        Path f = paths.escalations().resolve(Ids.itemKey(item.id) + ".md");
        StringBuilder b = new StringBuilder();
        b.append("# Escalation: ").append(item.id).append("\n\n- Flow: `").append(item.flowId).append("`\n- Scenario: ")
                .append(item.title).append("\n- Status: `").append(s.status.id()).append("`\n- Attempts: ").append(s.attempts)
                .append("\n- Reason: ").append(reason).append('\n');
        if (s.lastError != null) {
            b.append("- Last failure: `").append(s.lastError.code).append("`\n\n```\n").append(firstLines(s.lastError.message, 20)).append("\n```\n");
        }
        b.append("\n## Suggested next step\n\n");
        b.append(reason.startsWith("defect:") || reason.startsWith("Confirmed")
                ? "Confirm or reject the defect in chat (`/gta`), then file an issue with the scenario, expected and actual values.\n"
                : "Look at the last failure and the brief, fix the cause (environment, spec or test), then answer 'A' to retry in `/gta`.\n");
        Json.writeStringAtomic(f, b.toString());
        git.commit(List.of(paths.rel(f)), "docs(gta): escalate " + item.id + "\n\nGta-Item: " + item.id);
    }

    // ------------------------------------------------------------------ session end, verify, gate, report

    private Map<String, Object> finishSession(WorkState st, String why, String base) {
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
        Json.writeStringAtomic(dir.resolve("report.md"), reporter.markdown(st, plan, gate, false));
        Json.writeStringAtomic(dir.resolve("pr-comment.md"), reporter.markdown(st, plan, gate, true));
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

    private Map<String, Object> verifyInternal(WorkState st, List<String> ids) {
        List<String> unstable = new ArrayList<>();
        Set<String> classesDone = new LinkedHashSet<>();
        for (String id : ids) {
            PlanItem item;
            try {
                item = planItem(id);
            } catch (GtaException e) {
                continue;
            }
            if (item.isFoundation() || !classesDone.add(item.testClass + "|" + item.ref)) {
                continue;
            }
            for (int i = 0; i < config.qualityGates.stabilityRepeat; i++) {
                RunResult r = runItem(Ids.newRunId(git.head()), item, i == 0, true);
                if (!r.allPassed()) {
                    unstable.add(id);
                    WorkState.ItemState s = st.items.get(id);
                    if (s != null && s.status == ItemStatus.PASSED) {
                        s.lastError = lastError(s, ErrorCode.TEST_ASSERTION_FAILED, "unstable under pass^k / Asia/Kolkata timezone / de-DE locale / random order");
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
    private Gate.Result gateInternal(String base, List<Path> reportDirs) {
        List<TestCase> cases;
        if (reportDirs == null || reportDirs.isEmpty()) {
            RunResult all = new MavenRunner(paths, config).run(Ids.newRunId(git.head()), null,
                    new MavenRunner.Options(null, "gta", false, false), productionClasses(), testClasses());
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

    private String defaultBase() {
        for (String ref : List.of("origin/main", "origin/master", "main", "master")) {
            if (Proc.run(paths.root(), Proc.cmd("git", "rev-parse", "--verify", "--quiet", ref)).ok()) {
                return ref;
            }
        }
        return "HEAD";
    }

    // ------------------------------------------------------------------ status, abort, relock

    public Map<String, Object> status() {
        WorkState st = store.load();
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
                for (Path f : files.filter(p -> p.toString().endsWith(".spec.md")).sorted().toList()) {
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

    public Map<String, Object> abort(String flowId) {
        try (WorkLock ignored = WorkLock.acquire(paths.lock())) {
            WorkState st = store.load();
            if (flowId == null) {
                store.save(new WorkState());
                Files.deleteIfExists(paths.plan());
                return result("working state discarded; specs, shards and committed tests are kept", null);
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

    public Map<String, Object> relock(String itemId, String reason, String by) {
        PlanItem item = planItem(itemId);
        Path shardPath = paths.oracleShard(item.flowId);
        Map<String, OracleShard.Entry> shard = OracleShard.read(shardPath);
        OracleShard.Entry e = shard.get(itemId);
        if (e == null) {
            throw new GtaException(ErrorCode.USAGE_UNKNOWN_ITEM, "No committed oracle for " + itemId, "Only committed (passed) items can be relocked");
        }
        AssertionScanner.ScenarioOracle oracle = scanOracle(item);
        e.fingerprint = OracleLock.fingerprint(oracle.assertionStatements().stream().filter(x -> e.tokens.stream().anyMatch(x::contains)).toList());
        e.relockedBy = by;
        e.reason = reason;
        OracleShard.write(shardPath, shard);
        return result("relocked " + itemId + " by " + by + "; commit " + paths.rel(shardPath) + " so the PR shows it", null);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> touchSession(WorkState st, boolean takeover) {
        Map<String, Object> recovered = new LinkedHashMap<>();
        Instant now = Instant.now();
        boolean idle = st.session != null && st.session.lastSeenAt != null
                && Duration.between(Instant.parse(st.session.lastSeenAt), now).toMinutes() >= config.budgets.sessionIdleMinutes;
        if (st.session == null || takeover || st.sessionEnded || idle) {
            if (st.session != null) {
                for (WorkState.ItemState s : st.items.values()) {
                    if (s.status.active()) {
                        Path backup = paths.recover().resolve(Ids.itemKey(s.id) + "-" + now.getEpochSecond());
                        try {
                            PlanItem item = planItem(s.id);
                            ChangeChecker cc = new ChangeChecker(paths);
                            if (s.baseline != null) {
                                cc.changedSince(s.baseline).stream().filter(f -> ChangeChecker.matchesAny(f, item.ownsFiles))
                                        .forEach(f -> cc.backup(f, backup));
                            }
                        } catch (GtaException ignored) {
                            // item not in plan any more
                        }
                        st.move(s, ItemStatus.PENDING);
                        recovered.put(s.id, "reset to pending; files backed up to " + paths.rel(backup));
                    }
                }
            }
            WorkState.Session session = new WorkState.Session();
            session.id = Ids.newSessionId();
            session.startedAt = now.toString();
            st.session = session;
            st.itemsThisSession = 0;
            st.sessionPassed = new ArrayList<>();
            st.sessionEnded = false;
            st.consecutiveFailures = 0;
        }
        st.session.lastSeenAt = now.toString();
        return recovered;
    }

    private void touch(WorkState st) {
        if (st.session != null) {
            st.session.lastSeenAt = Instant.now().toString();
        }
    }

    private WorkState.ItemState requireToken(WorkState st, String itemId, String token, ItemStatus... allowed) {
        WorkState.ItemState s = st.item(itemId);
        if (s.token == null || !s.token.equals(token)) {
            throw new GtaException(ErrorCode.STATE_STALE_SESSION, "Token does not match the current brief of " + itemId,
                    "Run ./gtaw next to get the current brief and token");
        }
        if (List.of(allowed).stream().noneMatch(a -> a == s.status)) {
            throw new GtaException(ErrorCode.STATE_ILLEGAL_TRANSITION, itemId + " is " + s.status.id(), "Run ./gtaw next");
        }
        return s;
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
            String fqcn = e.ref.substring(e.ref.indexOf(':') + 1, e.ref.indexOf('#'));
            String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
            if (t.endsWith(fqcn.replace('.', '/') + ".java") || t.endsWith(simple + ".java") || t.equals(simple)) {
                return e.flowId;
            }
        }
        throw new GtaException(ErrorCode.USAGE_UNKNOWN_FLOW, "No flow found for " + target,
                "Use a flowId like order.create (see ./gtaw next without --target for candidates)");
    }

    private List<PlanItem> syncPlan(WorkState st, Spec spec, ServiceProfile profile) {
        List<PlanItem> fresh = Planner.plan(spec, registry, profile);
        List<PlanItem> plan = store.loadPlan();
        Map<String, PlanItem> existing = new LinkedHashMap<>();
        plan.stream().filter(i -> i.flowId.equals(spec.flowId())).forEach(i -> existing.put(i.id, i));
        List<PlanItem> result = new ArrayList<>();
        for (PlanItem f : fresh) {
            PlanItem old = existing.remove(f.id);
            WorkState.ItemState s = st.items.get(f.id);
            if (old != null && s != null && old.scenarioRevision.equals(f.scenarioRevision)) {
                result.add(old);
                continue;
            }
            if (s != null && !s.status.terminal()) {
                st.move(s, ItemStatus.CANCELLED);
            }
            WorkState.ItemState ns = new WorkState.ItemState();
            ns.id = f.id;
            if (s != null && s.status == ItemStatus.PASSED && old != null && old.scenarioRevision.equals(f.scenarioRevision)) {
                ns = s;
            }
            st.items.put(f.id, ns);
            result.add(f);
        }
        for (PlanItem gone : existing.values()) {
            WorkState.ItemState s = st.items.get(gone.id);
            if (s != null && !s.status.terminal()) {
                st.move(s, ItemStatus.CANCELLED);
            }
        }
        plan.removeIf(i -> i.flowId.equals(spec.flowId()));
        plan.addAll(result);
        store.savePlan(plan);
        // rebuild: tests that already exist for a fresh pending item are verified first
        for (PlanItem i : result) {
            WorkState.ItemState s = st.items.get(i.id);
            Path test = paths.root().resolve("src/test/java").resolve(i.testClass.replace('.', '/') + ".java");
            Map<String, OracleShard.Entry> shard = OracleShard.read(paths.oracleShard(i.flowId));
            if (s.status == ItemStatus.PENDING && s.attempts == 0 && Files.exists(test) && shard.containsKey(i.id)
                    && i.scenarioRevision.equals(shard.get(i.id).scenarioRevision)) {
                WorkState.Lock lock = new WorkState.Lock();
                lock.tokens = new ArrayList<>(shard.get(i.id).tokens);
                AssertionScanner.ScenarioOracle o = scanOracle(i);
                lock.statements = o.assertionStatements().stream().filter(x -> lock.tokens.stream().anyMatch(x::contains)).toList();
                lock.watched = o.watchedHash();
                lock.fingerprint = shard.get(i.id).fingerprint;
                s.lock = lock;
                s.baseline = new ChangeChecker(paths).capture();
                s.status = ItemStatus.VERIFYING;
            }
        }
        return result;
    }

    private PlanItem planItem(String id) {
        return store.loadPlan().stream().filter(i -> i.id.equals(id)).findFirst()
                .orElseThrow(() -> new GtaException(ErrorCode.USAGE_UNKNOWN_ITEM, "Unknown item " + id, "Run ./gtaw status"));
    }

    private RunResult runItem(String runId, PlanItem item, boolean variant, boolean random) {
        String tag = item.isFoundation() ? null : "scenario:" + item.ref;
        return new MavenRunner(paths, config).run(runId, item.id, new MavenRunner.Options(item.testClass, tag, variant, random),
                productionClasses(), testClasses());
    }

    private AssertionScanner.ScenarioOracle scanOracle(PlanItem item) {
        Path test = paths.root().resolve("src/test/java").resolve(item.testClass.replace('.', '/') + ".java");
        return AssertionScanner.scan(test, item.isFoundation() ? null : item.ref, paths.root().resolve("src/test/resources"));
    }

    private boolean reached(RunResult run) {
        if (run.compileError || run.cases.isEmpty()) {
            return false;
        }
        return run.cases.stream().anyMatch(c -> c.passed() || c.sutReached);
    }

    private Set<String> productionClasses() {
        return JavaIndex.of(paths.root().resolve("src/main/java")).classNames();
    }

    private Set<String> testClasses() {
        return JavaIndex.of(paths.root().resolve("src/test/java")).classNames();
    }

    private Map<String, Object> summarize(RunResult run) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("compileError", run.compileError);
        if (run.compileError) {
            out.put("compile", run.compileMessage);
        }
        out.put("tests", run.cases.size());
        out.put("passed", run.cases.stream().filter(TestCase::passed).count());
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

    private List<String> staleRefs(Spec spec) {
        RefResolver refs = RefResolver.of(paths.root());
        List<String> stale = new ArrayList<>();
        for (Spec.Requirement r : spec.requirements) {
            List<CommentMeta.Entry> entries = r.meta.entries();
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).key().equals("ref") && i + 1 < entries.size() && entries.get(i + 1).key().equals("refHash")) {
                    String ref = entries.get(i).value();
                    Optional<String> now = refs.hash(ref);
                    if (now.isEmpty() || !now.get().equals(entries.get(i + 1).value())) {
                        stale.add(ref);
                    }
                }
            }
        }
        return stale;
    }

    private boolean approvalStillValid(Spec spec) {
        if (spec.approvals.isEmpty()) {
            return false;
        }
        CommentMeta last = spec.approvals.get(spec.approvals.size() - 1);
        Map<String, String> approved = new LinkedHashMap<>();
        for (String pair : last.getOr("hashes", "").split(",")) {
            if (pair.contains(":")) {
                approved.put(pair.substring(0, pair.indexOf(':')), pair.substring(pair.indexOf(':') + 1));
            }
        }
        for (Spec.Requirement r : spec.requirements) {
            String key = r.id().substring(r.id().indexOf('/') + 1);
            if (!requirementHash(spec, r).equals(approved.get(key))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Approval hash of one requirement, built from the model. Expected values of observed/defaulted scenarios are masked:
     * they are observations, not human-approved expectations, so correcting them does not void the approval.
     */
    static String requirementHash(Spec spec, Spec.Requirement r) {
        StringBuilder b = new StringBuilder(r.title).append('|').append(r.text()).append('|').append(String.join(",", r.refs()));
        for (Spec.Scenario s : r.scenarios) {
            boolean mask = "observed".equals(s.source()) || "defaulted".equals(s.source());
            b.append('|').append(s.id()).append(s.title).append(s.source()).append(String.join(";", s.whens));
            for (String t : s.thens) {
                b.append(';').append(mask ? t.replaceAll("`[^`]*`", "`?`") : t);
            }
        }
        return Hashes.normalizedShort(b.toString());
    }

    private List<String> ownedFieldViolations(String flowId, Spec now) {
        Path snap = snapshotPath(flowId);
        if (!Files.exists(snap)) {
            List<String> v = new ArrayList<>();
            if (!now.answers.isEmpty() || !now.approvals.isEmpty()) {
                v.add("Answers section");
            }
            now.scenarios().stream().filter(s -> s.source().equals("confirmed") || s.source().equals("defaulted"))
                    .forEach(s -> v.add(s.id() + " source=" + s.source()));
            if ("approved".equals(now.status())) {
                v.add("status=approved");
            }
            now.questions.stream().filter(Spec.Question::answered).forEach(q -> v.add("tick on question " + q.number));
            return v;
        }
        Spec before;
        try {
            before = SpecParser.parseText(Files.readString(snap, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return List.of();
        }
        List<String> v = new ArrayList<>();
        if (!before.status().equals(now.status())) {
            v.add("status");
        }
        if (!before.rev().equals(now.rev())) {
            v.add("rev");
        }
        if (!before.answers.stream().map(CommentMeta::format).toList().equals(now.answers.stream().map(CommentMeta::format).toList())
                || !before.approvals.stream().map(CommentMeta::format).toList().equals(now.approvals.stream().map(CommentMeta::format).toList())) {
            v.add("Answers section");
        }
        for (Spec.Scenario s : now.scenarios()) {
            Optional<Spec.Scenario> b = before.scenario(s.id());
            String was = b.map(Spec.Scenario::source).orElse(null);
            boolean ownedNow = s.source().equals("confirmed") || s.source().equals("defaulted");
            boolean ownedBefore = "confirmed".equals(was) || "defaulted".equals(was);
            if ((ownedNow || ownedBefore) && !s.source().equals(was)) {
                v.add(s.id() + " source");
            }
        }
        for (Spec.Question q : now.questions) {
            before.questions.stream().filter(bq -> bq.id() != null && bq.id().equals(q.id())).findFirst().ifPresent(bq -> {
                if (!bq.chosen().map(o -> o.letter).equals(q.chosen().map(o -> o.letter))) {
                    v.add("tick on question " + q.number);
                }
            });
        }
        return v;
    }

    private Path snapshotPath(String flowId) {
        return paths.work().resolve("snapshots").resolve(flowId + ".spec.md");
    }

    private void snapshot(String flowId) {
        try {
            Path snap = snapshotPath(flowId);
            Files.createDirectories(snap.getParent());
            Files.copy(paths.spec(flowId), snap, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeSpec(Path specPath, String text) {
        Json.writeStringAtomic(specPath, text.endsWith("\n") ? text : text + "\n");
    }

    static String bump(String rev) {
        int n = 0;
        try {
            n = Integer.parseInt(rev.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException ignored) {
            // R0
        }
        return "R" + (n + 1);
    }

    private static String firstLines(String text, int n) {
        if (text == null) {
            return "";
        }
        String[] lines = text.split("\\R");
        return String.join("\n", java.util.Arrays.copyOf(lines, Math.min(n, lines.length)));
    }

    private static Map<String, Object> result(String message, Map<String, Object> more) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", message);
        if (more != null) {
            out.putAll(more);
        }
        return out;
    }
}
