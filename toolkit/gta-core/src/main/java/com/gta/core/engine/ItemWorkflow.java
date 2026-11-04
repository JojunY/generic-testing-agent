package com.gta.core.engine;

import static com.gta.core.engine.NodeResults.answerNode;
import static com.gta.core.engine.NodeResults.firstLines;
import static com.gta.core.engine.NodeResults.node;
import static com.gta.core.engine.NodeResults.result;

import com.gta.core.brief.BriefWriter;
import com.gta.core.change.ChangeChecker;
import com.gta.core.config.GtaConfig;
import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.oracle.AssertionScanner;
import com.gta.core.oracle.OracleLock;
import com.gta.core.oracle.OracleShard;
import com.gta.core.plan.PlanItem;
import com.gta.core.plan.Planner;
import com.gta.core.profile.ServiceProfile;
import com.gta.core.registry.CapabilityRegistry;
import com.gta.core.run.FailureClassifier;
import com.gta.core.run.RunResult;
import com.gta.core.run.TestOutcome;
import com.gta.core.spec.AnswerParser;
import com.gta.core.spec.Spec;
import com.gta.core.spec.SpecEditor;
import com.gta.core.spec.SpecParser;
import com.gta.core.state.ItemStatus;
import com.gta.core.state.StateStore;
import com.gta.core.state.WorkState;
import com.gta.core.util.AtomicFiles;
import com.gta.core.util.Git;
import com.gta.core.util.Ids;
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
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Item lifecycle (plan §5): dispatch, check, submit, oracle review, triage, repair, known-defect pins, commits and plan sync. */
final class ItemWorkflow {
    private static final Pattern EXPECTED_ACTUAL = Pattern.compile(
            "expected:\\s*<?\"?([^>\"\\n]+?)\"?>?\\s*but was:\\s*<?\"?([^>\"\\n]+?)\"?>?\\s*$",
            Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern ASSERTJ =
            Pattern.compile("expected:\\s*\"?(.+?)\"?\\s*\\n\\s*but was:\\s*\"?(.+?)\"?\\s*(\\n|$)");
    private static final List<String> TRIAGE_VERDICTS = List.of(
            "test-defect",
            "oracle-defect",
            "product-defect",
            "spec-error",
            "spec-gap",
            "flaky",
            "environment",
            "expected-change");

    private final EngineContext ctx;
    private final GtaPaths paths;
    private final GtaConfig config;
    private final CapabilityRegistry registry;
    private final StateStore store;
    private final Git git;
    private final BriefWriter briefs;

    ItemWorkflow(EngineContext ctx) {
        this.ctx = ctx;
        this.paths = ctx.paths;
        this.config = ctx.config;
        this.registry = ctx.registry;
        this.store = ctx.store;
        this.git = ctx.git;
        this.briefs = ctx.briefs;
    }

    /**
     * The next node for the items of one flow, or {@link Dispatch#sessionOver(String)} when nothing is left for this session;
     * the engine then runs the session end. Keeping that decision out of here keeps this class independent of {@link SessionEnd}.
     */
    record Dispatch(Map<String, Object> node, String sessionOverReason) {
        static Dispatch of(Map<String, Object> node) {
            return new Dispatch(node, null);
        }

        static Dispatch sessionOver(String reason) {
            return new Dispatch(null, reason);
        }
    }

    Dispatch dispatch(WorkState st, Spec spec, ServiceProfile profile, List<PlanItem> items) {
        if (st.consecutiveFailures >= config.budgets.maxConsecutiveFailures) {
            return Dispatch.of(
                    node(
                            "escalate",
                            Map.of(
                                    "message",
                                    st.consecutiveFailures
                                            + " items in a row needed a human; the run is paused. Resolve them (see ./gtaw status), then run /gta again.")));
        }
        for (PlanItem item : items) {
            WorkState.ItemState s = st.items.get(item.id);
            switch (s.status) {
                case AUTHORING, REPAIRING -> {
                    // every brief gets its own token: items adopted after a rebuild have none, and a re-issued
                    // brief must invalidate the previous one
                    st.seq++;
                    s.token = BriefWriter.token(st.session.id, st.seq);
                    Path brief = briefs.author(st.seq, s, item, spec, profile);
                    return Dispatch.of(authorNode(item, s, brief));
                }
                case TRIAGING -> {
                    return Dispatch.of(reviewNode(st, s, item, spec, "triage"));
                }
                case REVIEWING -> {
                    return Dispatch.of(reviewNode(st, s, item, spec, "oracle-review"));
                }
                case NEEDS_HUMAN -> {
                    st.pending = ctx.prompts.humanDecision(s, item);
                    return Dispatch.of(answerNode(st.pending));
                }
                case VERIFYING -> {
                    // rebuilt after a lost work dir: verify the existing test first
                    return Dispatch.of(submitInternal(st, item, s, true));
                }
                default -> {
                    // terminal or pending: handled below
                }
            }
        }
        if (st.itemsThisSession >= config.budgets.maxItemsPerSession) {
            return Dispatch.sessionOver(
                    "session budget reached (" + st.itemsThisSession + " items); run /gta again to continue");
        }
        for (PlanItem item : items) {
            WorkState.ItemState s = st.items.get(item.id);
            if (s.status != ItemStatus.PENDING) {
                continue;
            }
            boolean depsOk = item.dependsOn.stream()
                    .allMatch(d -> st.items.containsKey(d) && st.items.get(d).status == ItemStatus.PASSED);
            if (!depsOk) {
                continue;
            }
            st.seq++;
            s.token = BriefWriter.token(st.session.id, st.seq);
            s.baseline = new ChangeChecker(paths).capture();
            st.move(s, ItemStatus.AUTHORING);
            Path brief = briefs.author(st.seq, s, item, spec, profile);
            return Dispatch.of(authorNode(item, s, brief));
        }
        boolean blocked =
                items.stream().anyMatch(i -> !st.items.get(i.id).status.terminal());
        return Dispatch.sessionOver(
                blocked
                        ? "remaining items are blocked by dependencies that did not pass"
                        : "all items of " + spec.flowId() + " are done");
    }

    Map<String, Object> authorNode(PlanItem item, WorkState.ItemState s, Path brief) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("itemId", item.id);
        extra.put("agent", "gta-test-author");
        extra.put("brief", paths.rel(brief));
        extra.put("token", s.token);
        extra.put("status", s.status.id());
        return node("author", extra);
    }

    Map<String, Object> reviewNode(WorkState st, WorkState.ItemState s, PlanItem item, Spec spec, String kind) {
        // the verdict must come back with this token: it binds the verdict to this review round
        st.seq++;
        s.token = BriefWriter.token(st.session.id, st.seq);
        String details;
        if (kind.equals("oracle-review")) {
            details = "The test passed. Check that it asserts exactly what the THEN clauses say, on the right object, "
                    + "and that inputs really exercise the WHEN. Test file: `" + GtaPaths.testSourceRel(item.testClass)
                    + "`."
                    + (s.structuralOracleChange
                            ? "\n\n**Locked assertions were restructured (values unchanged): confirm the meaning is the same.**"
                            : "")
                    + (s.inputsChangedAfterAssertion
                            ? "\n\n**Inputs/fixtures changed after an assertion failure: make sure they were not bent to make the test pass.**"
                            : "");
        } else {
            details = "The test failed with `" + (s.lastError == null ? "?" : s.lastError.code) + "`:\n\n```\n"
                    + (s.lastError == null || s.lastError.message == null ? "" : s.lastError.message) + "\n```\n\n"
                    + "Decide whether the test, the spec or the product is wrong. Never recommend changing the expected value to the actual value."
                    + (s.verdict != null && s.verdict.equals("flaky")
                            ? "\n\ngtaw re-ran it and got different results: likely `flaky`."
                            : "");
        }
        Path brief = briefs.review(st.seq, s, item, spec, kind, details);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("itemId", item.id);
        extra.put("agent", "gta-reviewer");
        extra.put("kind", kind);
        extra.put("brief", paths.rel(brief));
        extra.put("token", s.token);
        return node("review", extra);
    }

    Map<String, Object> applyDecision(WorkState st, WorkState.Pending p, AnswerParser.Parsed parsed, String text) {
        if (!(parsed instanceof AnswerParser.Choice c)) {
            throw new GtaException(
                    ErrorCode.USAGE_UNPARSEABLE_ANSWER, "Expected a single option letter", "Reply A, B or C");
        }
        WorkState.ItemState s = st.item(p.itemId);
        PlanItem item = store.planItem(p.itemId);
        String letter = c.letter();
        String msg;
        if ("defect".equals(p.type) && letter.equals("A")) {
            s.intentOverride = "known-defect";
            s.reason = "confirmed product defect: pin with @KnownDefect";
            st.move(s, ItemStatus.PENDING);
            ctx.escalations.writeEscalation(
                    item,
                    s,
                    "Confirmed as a product defect by a human: \"" + text + "\". Issue draft: the behaviour of "
                            + item.ref + " violates the approved spec.");
            msg = "defect confirmed; the test will be pinned with @KnownDefect";
        } else if ("defect".equals(p.type) && letter.equals("B")) {
            st.move(s, ItemStatus.CANCELLED);
            WorkState.SpecWork sw = st.specWork.computeIfAbsent(item.flowId, k -> new WorkState.SpecWork());
            sw.needsRevision = true;
            sw.answersToApply = List.of("Human says the expectation of " + item.ref
                    + " is wrong: revise it and add an open question if unsure");
            Path specPath = paths.spec(item.flowId);
            ctx.specs.writeSpec(
                    specPath,
                    new SpecEditor(SpecParser.parse(specPath).text)
                            .setHeader("status", "in-review")
                            .text());
            msg = "spec sent back for revision";
        } else if (("defect".equals(p.type) && letter.equals("C"))
                || ("escalation".equals(p.type) && letter.equals("A"))) {
            st.move(s, ItemStatus.PENDING);
            s.attempts = 0;
            s.checks = 0; // a retry is a fresh start: the check budget too
            s.lastError = null;
            st.consecutiveFailures = 0;
            msg = "item reset; it will be retried";
        } else if ("escalation".equals(p.type) && letter.equals("B")) {
            st.move(s, ItemStatus.CANCELLED);
            msg = "item cancelled";
        } else {
            throw new GtaException(
                    ErrorCode.USAGE_UNPARSEABLE_ANSWER,
                    "Option " + letter + " is not available",
                    "Pick one of the listed letters");
        }
        st.newConfirmations.add(p.itemId + ": \"" + text + "\" (" + msg + ")");
        st.pending = null;
        return result(msg, null);
    }

    public Map<String, Object> check(String itemId, String token) {
        try (WorkLock ignored = WorkLock.acquire(paths.lock())) {
            WorkState st = store.load();
            WorkState.ItemState s = requireToken(st, itemId, token, ItemStatus.AUTHORING, ItemStatus.REPAIRING);
            if (s.checks >= config.budgets.maxChecksPerItem) {
                throw new GtaException(
                        ErrorCode.STATE_BUDGET_EXHAUSTED,
                        "Check budget used (" + s.checks + ")",
                        "Submit with ./gtaw submit --item " + itemId + " --token " + token);
            }
            s.checks++;
            PlanItem item = store.planItem(itemId);
            String runId = Ids.newRunId(git.head());
            RunResult run = ctx.runs.runItem(runId, item, false, false);
            AssertionScanner.ScenarioOracle oracle = ctx.runs.scanOracle(item);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("runId", runId);
            out.put("checksLeft", config.budgets.maxChecksPerItem - s.checks);
            out.putAll(TestRuns.summarize(run));
            if (!item.isFoundation() && !item.oracle.reviewerChecked && oracle.found()) {
                out.put(
                        "missingExpectedValues",
                        AssertionScanner.missingThenLiterals(item.oracle.then, oracle.tokens()));
            }
            if (s.lock == null && TestRuns.reached(run) && !item.isFoundation()) {
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
            WorkState.ItemState s = requireToken(st, itemId, token, ItemStatus.AUTHORING, ItemStatus.REPAIRING);
            Map<String, Object> out = submitInternal(st, store.planItem(itemId), s, false);
            store.save(st);
            return out;
        }
    }

    Map<String, Object> submitInternal(WorkState st, PlanItem item, WorkState.ItemState s, boolean rebuilt) {
        ItemStatus from = s.status;
        if (!rebuilt) {
            ChangeChecker.Result scope = new ChangeChecker(paths)
                    .check(
                            s.baseline,
                            item.ownsFiles,
                            paths.recover().resolve(Ids.itemKey(item.id) + "-" + st.seq),
                            true);
            st.move(s, ItemStatus.VERIFYING);
            if (!scope.ok()) {
                if (!scope.foreignCommits().isEmpty()) {
                    // history is never rewritten by gtaw: a human must look at the commits
                    s.lastError = lastError(
                            s,
                            ErrorCode.POLICY_OUT_OF_SCOPE_CHANGE,
                            "commits not made by gtaw: " + scope.foreignCommits());
                    return needsHuman(
                            st,
                            item,
                            s,
                            "commits made outside gtaw since the brief (" + String.join(", ", scope.foreignCommits())
                                    + ") touch " + scope.unrestorable()
                                    + ". Agents must not run git; revert or keep them yourself, then answer A to retry");
                }
                return fail(
                        st,
                        item,
                        s,
                        ErrorCode.POLICY_OUT_OF_SCOPE_CHANGE,
                        "Changed files outside ownsFiles were reverted: " + scope.restored()
                                + (scope.unrestorable().isEmpty()
                                        ? ""
                                        : "; not restorable (pre-existing changes): " + scope.unrestorable()));
            }
        }
        String runId = Ids.newRunId(git.head());
        RunResult run = ctx.runs.runItem(runId, item, false, false);
        s.evidenceRunId = runId;
        if (run.timedOut) {
            // counted like any other failure, so a hanging test reaches a human instead of being resubmitted forever
            return fail(
                    st,
                    item,
                    s,
                    ErrorCode.TEST_TIMEOUT,
                    "The test run exceeded budgets.nodeTimeoutMinutes (" + config.budgets.nodeTimeoutMinutes
                            + " min); look for missing timeouts (Awaitility, embedded brokers) — see " + run.logFile);
        }
        if (run.compileError) {
            return fail(st, item, s, ErrorCode.BUILD_COMPILE_ERROR, run.compileMessage);
        }
        if (run.cases.isEmpty()) {
            return fail(
                    st,
                    item,
                    s,
                    ErrorCode.TEST_NOT_FOUND,
                    "No test ran for " + item.testClass
                            + (item.isFoundation() ? "" : " with @Tag(\"scenario:" + item.ref + "\")"));
        }
        AssertionScanner.ScenarioOracle oracle = ctx.runs.scanOracle(item);
        if (!item.isFoundation()) {
            if (s.lock == null && TestRuns.reached(run)) {
                s.lock = OracleLock.create(oracle, item.oracle.then, runId);
            }
            if (!item.oracle.reviewerChecked) {
                List<String> missing = AssertionScanner.missingThenLiterals(item.oracle.then, oracle.tokens());
                if (!missing.isEmpty()) {
                    return fail(
                            st,
                            item,
                            s,
                            ErrorCode.POLICY_ORACLE_MISMATCH,
                            "Expected values from the spec are not asserted: " + missing);
                }
            }
            OracleLock.Check lockCheck = OracleLock.check(s.lock, oracle);
            if (lockCheck.verdict() == OracleLock.Verdict.MODIFIED) {
                return fail(
                        st,
                        item,
                        s,
                        ErrorCode.POLICY_ORACLE_MODIFIED,
                        "Locked expected values were changed or removed: " + lockCheck.missingTokens()
                                + ". Restore them; only a human can relock.");
            }
            s.structuralOracleChange = lockCheck.verdict() == OracleLock.Verdict.STRUCTURAL_CHANGE;
            if (s.lock != null
                    && s.lastError != null
                    && ErrorCode.find(s.lastError.code)
                            .map(ErrorCode::needsTriage)
                            .orElse(false)
                    && !Objects.equals(s.lock.watched, oracle.watchedHash())) {
                s.inputsChangedAfterAssertion = true;
            }
        }
        List<TestOutcome> failures = run.failures();
        if ("known-defect".equals(s.intentOverride)
                && !run.cases.isEmpty()
                && run.cases.stream().allMatch(c -> "known-defect".equals(c.outcome))) {
            st.move(s, ItemStatus.DEFECT_FOUND);
            commitItem(st, item, s, runId, "pin known defect " + item.ref);
            ctx.escalations.writeEscalation(
                    item,
                    s,
                    "Confirmed product defect, pinned with @KnownDefect (fingerprint "
                            + (s.lastError == null ? "-" : s.lastError.defectFingerprint) + ").");
            // not counted against the session budget: the item was already counted when it reached the human
            return result(
                    "defect-found: " + item.id + " pinned with @KnownDefect (build stays green)",
                    Map.of("runId", runId));
        }
        boolean anyPinned = run.cases.stream().anyMatch(c -> "known-defect".equals(c.outcome));
        if (anyPinned && !"known-defect".equals(s.intentOverride)) {
            // @KnownDefect turns a failure green, so only a human's confirmation may introduce it
            boolean pinnedOnly = run.cases.stream().allMatch(c -> "known-defect".equals(c.outcome));
            if (rebuilt && pinnedOnly && confirmedPin(item)) {
                st.move(s, ItemStatus.DEFECT_FOUND);
                return result(
                        "defect-found: " + item.id + " (adopted; the pin was confirmed earlier)",
                        Map.of("runId", runId));
            }
            return fail(
                    st,
                    item,
                    s,
                    ErrorCode.POLICY_UNCONFIRMED_KNOWN_DEFECT,
                    "@KnownDefect may only be added after a human confirmed the defect; remove it and let the failure be triaged");
        }
        if (failures.isEmpty()) {
            if ("reproducing".equals(item.intent)) {
                return fail(
                        st,
                        item,
                        s,
                        ErrorCode.TEST_ASSERTION_FAILED,
                        "A reproducing test must fail for the reported reason, but it passed");
            }
            if (config.qualityGates.oracleReview && !item.isFoundation()) {
                st.move(s, ItemStatus.REVIEWING);
                return result(
                        "passed run " + runId + "; waiting for the reviewer's oracle review", Map.of("runId", runId));
            }
            return markPassed(st, item, s, runId);
        }
        TestOutcome first = failures.get(0);
        // a code this version does not know is treated as unclassified: triage is the safe default
        ErrorCode code = ErrorCode.find(first.code).orElse(ErrorCode.TEST_SUT_ERROR);
        String message = (first.failureType == null ? "" : first.failureType + ": ")
                + (first.message == null ? "" : first.message);
        if (code.isMechanical()) {
            return fail(st, item, s, code, message + "\n" + firstLines(first.stack, 8));
        }
        // assertion / SUT error / timeout: re-run twice before triage (plan §5.3)
        int consistent = 0;
        for (int i = 0; i < 2; i++) {
            RunResult again = ctx.runs.runItem(Ids.newRunId(git.head()), item, false, false);
            if (again.timedOut || !again.failures().isEmpty()) {
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
        return result(
                code.name() + " (" + (consistent == 2 ? "reproducible" : "flaky: " + consistent + "/2 re-runs failed")
                        + "); waiting for the reviewer's triage",
                Map.of("runId", runId, "code", code.name()));
    }

    public Map<String, Object> submitReview(String itemId, String token, String verdict, String reason) {
        try (WorkLock ignored = WorkLock.acquire(paths.lock())) {
            WorkState st = store.load();
            WorkState.ItemState s = requireToken(st, itemId, token, ItemStatus.REVIEWING, ItemStatus.TRIAGING);
            PlanItem item = store.planItem(itemId);
            Map<String, Object> out;
            if (s.status == ItemStatus.REVIEWING) {
                if ("accept".equals(verdict)) {
                    out = markPassed(st, item, s, s.evidenceRunId);
                } else if ("reject".equals(verdict)) {
                    out = fail(st, item, s, ErrorCode.TEST_CODE_ERROR, "Reviewer rejected the oracle: " + reason);
                } else {
                    throw new GtaException(
                            ErrorCode.USAGE_INVALID_ARGUMENT,
                            "Oracle review verdict must be accept or reject",
                            "Use --verdict accept|reject");
                }
            } else if (s.status == ItemStatus.TRIAGING) {
                if (!TRIAGE_VERDICTS.contains(verdict)) {
                    throw new GtaException(
                            ErrorCode.USAGE_INVALID_ARGUMENT,
                            "Unknown triage verdict " + verdict,
                            "Use one of " + TRIAGE_VERDICTS);
                }
                out = applyTriage(st, item, s, verdict, reason);
            } else {
                throw new GtaException(
                        ErrorCode.STATE_ILLEGAL_TRANSITION,
                        itemId + " is " + s.status.id() + ", not under review",
                        "Run ./gtaw next");
            }
            s.token = null; // one verdict per review round
            store.save(st);
            return out;
        }
    }

    Map<String, Object> applyTriage(WorkState st, PlanItem item, WorkState.ItemState s, String verdict, String reason) {
        boolean humanBacked = "confirmed".equals(item.oracle.source) || "documented".equals(item.oracle.source);
        s.verdict = verdict;
        switch (verdict) {
            case "test-defect", "flaky" -> {
                st.move(s, ItemStatus.REPAIRING);
                s.attempts++;
                s.reason =
                        ("flaky".equals(verdict) ? "flaky: only waiting/synchronisation may change. " : "test defect: ")
                                + reason;
                if (s.attempts >= config.budgets.maxAttemptsPerItem) {
                    return needsHuman(st, item, s, "repair attempts exhausted after triage: " + reason);
                }
                return result(item.id + " back to the author (" + verdict + "); oracle stays locked", null);
            }
            case "product-defect" -> {
                if (!humanBacked) {
                    throw new GtaException(
                            ErrorCode.USAGE_INVALID_ARGUMENT,
                            "The oracle of " + item.id + " is " + item.oracle.source
                                    + "; it cannot prove a product defect",
                            "Use spec-error (observation was wrong) or test-defect");
                }
                Map<String, Object> out = needsHuman(st, item, s, "defect: " + reason);
                st.consecutiveFailures = 0; // a suspected product defect is a finding, not an authoring failure
                return out;
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
    Map<String, Object> autoCorrect(WorkState st, PlanItem item, WorkState.ItemState s, String reason) {
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
            return needsHuman(
                    st,
                    item,
                    s,
                    "spec-error on an observed expectation, but the actual value could not be read from the failure: "
                            + reason);
        }
        Path specPath = paths.spec(item.flowId);
        Spec spec = SpecParser.parse(specPath);
        String newRev = SpecWorkflow.bump(spec.rev());
        ctx.specs.writeSpec(
                specPath,
                new SpecEditor(spec.text)
                        .replaceThenLiteral(item.ref, expected, actual)
                        .setHeader("rev", newRev)
                        .text());
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
            s.lock.tokens.removeIf(exp::equals);
            s.lock.tokens.add(actual);
            s.lock.statements.removeIf(stmt -> stmt.contains(exp));
        }
        st.move(s, ItemStatus.REPAIRING);
        s.reason = "spec corrected from the actual behaviour: `" + expected + "` → `" + actual
                + "`; update the assertion to the new expected value";
        st.pendingReview.add(item.ref + ": `" + expected + "` → `" + actual + "` (" + reason + ")");
        return result(
                item.id + ": observed THEN corrected to `" + actual + "` (" + item.flowId + "@" + newRev
                        + "); listed for re-confirmation; back to the author",
                null);
    }

    Map<String, Object> fail(WorkState st, PlanItem item, WorkState.ItemState s, ErrorCode code, String message) {
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

    static WorkState.LastError lastError(WorkState.ItemState s, ErrorCode code, String message) {
        WorkState.LastError e = new WorkState.LastError();
        e.code = code.name();
        e.message = message;
        e.fingerprint = FailureClassifier.fingerprint(code.name(), message);
        e.repeats = s.lastError != null && e.fingerprint.equals(s.lastError.fingerprint) ? s.lastError.repeats + 1 : 1;
        return e;
    }

    Map<String, Object> needsHuman(WorkState st, PlanItem item, WorkState.ItemState s, String reason) {
        s.reason = reason;
        if (s.status != ItemStatus.NEEDS_HUMAN) {
            st.move(s, ItemStatus.NEEDS_HUMAN);
        }
        st.consecutiveFailures++;
        st.itemsThisSession++;
        ctx.escalations.writeEscalation(item, s, reason);
        return result("needs-human: " + item.id + " — " + reason, null);
    }

    Map<String, Object> markPassed(WorkState st, PlanItem item, WorkState.ItemState s, String runId) {
        st.move(s, ItemStatus.PASSED);
        if ("known-defect".equals(s.intentOverride)) {
            s.intentOverride = null;
        }
        commitItem(st, item, s, runId, item.title);
        st.sessionPassed.add(item.id);
        st.itemsThisSession++;
        st.consecutiveFailures = 0;
        return result(
                "passed: " + item.id + " (run " + runId + ", commit "
                        + (s.commit == null ? "-" : s.commit.substring(0, 7)) + ")",
                null);
    }

    void commitItem(WorkState st, PlanItem item, WorkState.ItemState s, String runId, String summary) {
        WorkState.Baseline baseline = s.baseline == null ? new ChangeChecker(paths).capture() : s.baseline;
        List<String> files = new ArrayList<>();
        List<String> mixed = new ArrayList<>();
        for (String changed : new ChangeChecker(paths).changedSince(baseline)) {
            if (!ChangeChecker.matchesAny(changed, item.ownsFiles)) {
                continue;
            }
            boolean testCode =
                    changed.startsWith(GtaPaths.TEST_JAVA + "/") || changed.startsWith(GtaPaths.TEST_RESOURCES + "/");
            if (baseline.dirty.containsKey(changed) && !testCode) {
                // e.g. pom.xml with the developer's own uncommitted edits: never commit those on their behalf.
                // Test classes are shared by the items of a flow, so earlier items' uncommitted work is gta's own.
                mixed.add(changed);
            } else {
                files.add(changed);
            }
        }
        if (!mixed.isEmpty()) {
            s.reason = "not committed because they also contain your uncommitted edits: " + mixed
                    + " — commit them yourself";
        }
        Path shardPath = paths.oracleShard(item.flowId);
        String shardBefore = readIfExists(shardPath);
        if (!item.isFoundation() && s.lock != null) {
            Map<String, OracleShard.Entry> shard = OracleShard.read(shardPath);
            OracleShard.Entry e = new OracleShard.Entry();
            e.scenarioRevision = item.scenarioRevision;
            e.fingerprint = OracleLock.fingerprint(ctx.runs.scanOracle(item).assertionStatements().stream()
                    .filter(x -> s.lock.tokens.stream().anyMatch(x::contains))
                    .toList());
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
        try {
            ctx.commit(files, msg).ifPresent(sha -> s.commit = sha);
        } catch (GtaException e) {
            // keep the shard and the commit together: the next submit must not see gtaw's own write as an agent change
            restore(shardPath, shardBefore);
            throw e;
        }
    }

    private static String readIfExists(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void restore(Path file, String content) {
        try {
            if (content == null) {
                Files.deleteIfExists(file);
            } else {
                AtomicFiles.write(file, content);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A pin is confirmed when gtaw committed the item's known-defect record (see EscalationWriter); the working tree does not count. */
    boolean confirmedPin(PlanItem item) {
        String rel = paths.rel(paths.escalations().resolve(Ids.itemKey(item.id) + ".md"));
        return git.show("HEAD", rel)
                .map(text -> text.contains(EscalationWriter.DEFECT_FOUND_MARKER))
                .orElse(false);
    }

    WorkState.ItemState requireToken(WorkState st, String itemId, String token, ItemStatus... allowed) {
        WorkState.ItemState s = st.item(itemId);
        if (s.token == null || !s.token.equals(token)) {
            throw new GtaException(
                    ErrorCode.STATE_STALE_SESSION,
                    "Token does not match the current brief of " + itemId,
                    "Run ./gtaw next to get the current brief and token");
        }
        if (List.of(allowed).stream().noneMatch(a -> a == s.status)) {
            throw new GtaException(
                    ErrorCode.STATE_ILLEGAL_TRANSITION, itemId + " is " + s.status.id(), "Run ./gtaw next");
        }
        return s;
    }

    private static void dropPendingDecision(WorkState st, String itemId) {
        if (st.pending != null && itemId.equals(st.pending.itemId)) {
            st.pending = null;
        }
    }

    List<PlanItem> syncPlan(WorkState st, Spec spec, ServiceProfile profile) {
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
            dropPendingDecision(st, f.id); // a human decision about the old item no longer applies
            WorkState.ItemState ns = new WorkState.ItemState();
            ns.id = f.id;
            st.items.put(f.id, ns);
            result.add(f);
        }
        for (PlanItem gone : existing.values()) {
            WorkState.ItemState s = st.items.get(gone.id);
            if (s != null && !s.status.terminal()) {
                st.move(s, ItemStatus.CANCELLED);
            }
            dropPendingDecision(st, gone.id);
        }
        plan.removeIf(i -> i.flowId.equals(spec.flowId()));
        plan.addAll(result);
        store.savePlan(plan);
        // rebuild: tests that already exist for a fresh pending item are verified first
        Map<String, OracleShard.Entry> shard = OracleShard.read(paths.oracleShard(spec.flowId()));
        for (PlanItem i : result) {
            WorkState.ItemState s = st.items.get(i.id);
            Path test = paths.testSource(i.testClass);
            if (i.isFoundation()
                    && s.status == ItemStatus.PENDING
                    && s.attempts == 0
                    && Files.exists(test)
                    && git.isTracked(paths.rel(test))) {
                // the committed seed test is adopted after one verification run instead of being written again
                s.baseline = new ChangeChecker(paths).capture();
                st.move(s, ItemStatus.VERIFYING); // adopt: verify the existing test before trusting it
                continue;
            }
            if (s.status == ItemStatus.PENDING
                    && s.attempts == 0
                    && Files.exists(test)
                    && shard.containsKey(i.id)
                    && i.scenarioRevision.equals(shard.get(i.id).scenarioRevision)) {
                WorkState.Lock lock = new WorkState.Lock();
                lock.tokens = new ArrayList<>(shard.get(i.id).tokens);
                AssertionScanner.ScenarioOracle o = ctx.runs.scanOracle(i);
                lock.statements = o.assertionStatements().stream()
                        .filter(x -> lock.tokens.stream().anyMatch(x::contains))
                        .toList();
                lock.watched = o.watchedHash();
                lock.fingerprint = shard.get(i.id).fingerprint;
                s.lock = lock;
                s.baseline = new ChangeChecker(paths).capture();
                st.move(s, ItemStatus.VERIFYING); // adopt: verify the existing test before trusting it
            }
        }
        return result;
    }
}
