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
import com.gta.core.plan.PlanItem;
import com.gta.core.profile.ServiceProfile;
import com.gta.core.run.FailureClassifier;
import com.gta.core.run.RunResult;
import com.gta.core.run.TestOutcome;
import com.gta.core.spec.Spec;
import com.gta.core.state.ItemStatus;
import com.gta.core.state.StateStore;
import com.gta.core.state.WorkState;
import com.gta.core.util.Git;
import com.gta.core.util.Ids;
import com.gta.core.util.ProcessRunner;
import com.gta.core.util.WorkLock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Item lifecycle (plan §5): dispatch, check, submit and oracle review. Triage, commits and plan sync are their own classes. */
final class ItemWorkflow {
    private final EngineContext ctx;
    private final GtaPaths paths;
    private final GtaConfig config;
    private final StateStore store;
    private final Git git;
    private final BriefWriter briefs;

    ItemWorkflow(EngineContext ctx) {
        this.ctx = ctx;
        this.paths = ctx.paths;
        this.config = ctx.config;
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
        PlanItem conflict = null;
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
            if (!s.sharesDeveloperFile && takenByDeveloper(item)) {
                // never decide for the developer: ask once the items without a conflict are done (plan §9.3)
                if (conflict == null) {
                    conflict = item;
                }
                continue;
            }
            st.seq++;
            s.token = BriefWriter.token(st.session.id, st.seq);
            s.baseline = new ChangeChecker(paths).capture();
            st.move(s, ItemStatus.AUTHORING);
            Path brief = briefs.author(st.seq, s, item, spec, profile);
            return Dispatch.of(authorNode(item, s, brief));
        }
        if (conflict != null) {
            st.pending = ctx.prompts.namingConflict(conflict, lastAuthor(conflict));
            return Dispatch.of(NodeResults.answerNode(st.pending));
        }
        boolean blocked =
                items.stream().anyMatch(i -> !st.items.get(i.id).status.terminal());
        return Dispatch.sessionOver(
                blocked
                        ? "remaining items are blocked by dependencies that did not pass"
                        : "all items of " + spec.flowId() + " are done");
    }

    private static final Pattern GTA_TAG = Pattern.compile("@Tag\\(\\s*\"gta\"\\s*\\)");

    /** The item's test class exists and is not a gta test (no {@code @Tag("gta")}): a developer owns that name. */
    boolean takenByDeveloper(PlanItem item) {
        Path test = paths.testSource(item.testClass);
        if (!Files.isRegularFile(test)) {
            return false;
        }
        try {
            return !GTA_TAG.matcher(Files.readString(test, StandardCharsets.UTF_8))
                    .find();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String lastAuthor(PlanItem item) {
        ProcessRunner.Result r = ProcessRunner.run(
                paths.root(),
                ProcessRunner.cmd("git", "log", "-1", "--format=%an", "--", paths.testSourceRel(item.testClass)));
        return r.ok() && !r.output().isBlank() ? r.output().strip() : "not committed yet";
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
                    + "and that inputs really exercise the WHEN. Test file: `" + paths.testSourceRel(item.testClass)
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

    private String buildLog(RunResult run) {
        try {
            return run.logFile == null ? "" : Files.readString(paths.root().resolve(run.logFile));
        } catch (java.io.IOException e) {
            return "";
        }
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
        if (!run.timedOut && (run.compileError || run.cases.isEmpty())) {
            // rules before the LLM: fix deterministic slips in the item's own test, then run again once
            List<String> repaired = RuleRepair.apply(
                    paths.testSource(item.testClass), item, run.compileError ? buildLog(run) : "", run.cases.isEmpty());
            if (!repaired.isEmpty()) {
                repaired.forEach(r -> com.gta.core.state.Lessons.record(
                        paths, "rule-repair", item.capability + " " + item.layer + " test", r));
                runId = Ids.newRunId(git.head());
                run = ctx.runs.runItem(runId, item, false, false);
                s.evidenceRunId = runId;
            }
        }
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
            ctx.committer.commitItem(st, item, s, runId, "pin known defect " + item.ref);
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
            if (rebuilt && pinnedOnly && ctx.committer.confirmedPin(item)) {
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
                    com.gta.core.state.Lessons.record(
                            paths, "reviewer", item.capability + " " + item.layer + " test", reason);
                    out = fail(st, item, s, ErrorCode.TEST_CODE_ERROR, "Reviewer rejected the oracle: " + reason);
                } else {
                    throw new GtaException(
                            ErrorCode.USAGE_INVALID_ARGUMENT,
                            "Oracle review verdict must be accept or reject",
                            "Use --verdict accept|reject");
                }
            } else if (s.status == ItemStatus.TRIAGING) {
                if (!Triage.VERDICTS.contains(verdict)) {
                    throw new GtaException(
                            ErrorCode.USAGE_INVALID_ARGUMENT,
                            "Unknown triage verdict " + verdict,
                            "Use one of " + Triage.VERDICTS);
                }
                out = ctx.triage.applyTriage(st, item, s, verdict, reason);
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
        ctx.committer.commitItem(st, item, s, runId, item.title);
        st.sessionPassed.add(item.id);
        st.itemsThisSession++;
        st.consecutiveFailures = 0;
        return result(
                "passed: " + item.id + " (run " + runId + ", commit "
                        + (s.commit == null ? "-" : s.commit.substring(0, 7)) + ")",
                null);
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
}
