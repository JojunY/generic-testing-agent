package com.gta.core.engine;

import com.gta.core.brief.Briefs;
import com.gta.core.change.ChangeChecker;
import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.profile.RefResolver;
import com.gta.core.profile.ServiceProfile;
import com.gta.core.registry.Registry;
import com.gta.core.spec.AnswerParser;
import com.gta.core.spec.CommentMeta;
import com.gta.core.spec.Spec;
import com.gta.core.spec.SpecEditor;
import com.gta.core.spec.SpecLinter;
import com.gta.core.spec.SpecParser;
import com.gta.core.state.StateStore;
import com.gta.core.state.WorkState;
import com.gta.core.util.AtomicFiles;
import com.gta.core.util.Git;
import com.gta.core.util.Hashes;
import com.gta.core.util.WorkLock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.gta.core.engine.NodeResults.node;
import static com.gta.core.engine.NodeResults.result;

/** Spec lifecycle (plan §3): analyze briefs, spec submission and lint, answer rounds, approval, snapshots of gtaw-owned fields. */
final class SpecWorkflow {
    private final EngineContext ctx;
    private final GtaPaths paths;
    private final Registry registry;
    private final StateStore store;
    private final Git git;
    private final Briefs briefs;

    SpecWorkflow(EngineContext ctx) {
        this.ctx = ctx;
        this.paths = ctx.paths;
        this.registry = ctx.registry;
        this.store = ctx.store;
        this.git = ctx.git;
        this.briefs = ctx.briefs;
    }

    Map<String, Object> analyzeNode(WorkState st, WorkState.SpecWork sw, String flowId, ServiceProfile.EntryPoint entry,
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

    Map<String, Object> applyAnswers(WorkState st, WorkState.Pending p, AnswerParser.Parsed parsed, String text) {
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
            p.prompt = ctx.prompts.renderQuestions(after, p.round);
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
        p.prompt = ctx.prompts.renderApproval(revised);
        return result("answers recorded; spec is now " + p.flowId + "@" + newRev, Map.of("prompt", p.prompt));
    }

    Map<String, Object> applyApproval(WorkState st, WorkState.Pending p, AnswerParser.Parsed parsed, String text) {
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

    public Map<String, Object> submitSpec(String flowId, String token) {
        try (WorkLock ignored = WorkLock.acquire(paths.lock())) {
            WorkState st = store.load();
            ctx.sessions.touch(st);
            WorkState.SpecWork sw = st.specWork.get(flowId);
            if (sw == null || sw.token == null || !sw.token.equals(token)) {
                throw new GtaException(ErrorCode.STATE_STALE_SESSION, "Token does not match the open analyze node of " + flowId,
                        "Run ./gtaw next to get a fresh brief and token");
            }
            Path specPath = paths.spec(flowId);
            String specRel = paths.rel(specPath);
            ChangeChecker.Result scope = new ChangeChecker(paths).check(sw.baseline,
                    List.of(specRel, paths.rel(paths.specs().resolve("service-overview.md"))), paths.recover().resolve("analyze-" + st.seq), true);
            if (!scope.ok()) {
                sw.attempts++;
                store.save(st);
                throw new GtaException(ErrorCode.POLICY_OUT_OF_SCOPE_CHANGE, "The analyst may only change " + specRel,
                        scope.foreignCommits().isEmpty()
                                ? "Reverted (backup in .gta/work/recover/): " + scope.restored()
                                : "Commits not made by gtaw " + scope.foreignCommits() + " must be reverted by a human; agents must not run git")
                        .detail("outOfScope", scope.outOfScope());
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

    List<String> staleRefs(Spec spec) {
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

    boolean approvalStillValid(Spec spec) {
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

    List<String> ownedFieldViolations(String flowId, Spec now) {
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

    Path snapshotPath(String flowId) {
        return paths.work().resolve("snapshots").resolve(flowId + ".spec.md");
    }

    void snapshot(String flowId) {
        try {
            Path snap = snapshotPath(flowId);
            Files.createDirectories(snap.getParent());
            Files.copy(paths.spec(flowId), snap, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void writeSpec(Path specPath, String text) {
        AtomicFiles.write(specPath, text.endsWith("\n") ? text : text + "\n");
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
}
