package com.gta.core.engine;

import static com.gta.core.engine.NodeResults.result;

import com.gta.core.config.GtaConfig;
import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.plan.PlanItem;
import com.gta.core.spec.AnswerParser;
import com.gta.core.spec.Spec;
import com.gta.core.spec.SpecEditor;
import com.gta.core.spec.SpecParser;
import com.gta.core.state.ItemStatus;
import com.gta.core.state.StateStore;
import com.gta.core.state.WorkState;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Failure triage, auto-correction of observed expectations and human decisions on items (plan §5.3, §5.5). */
final class Triage {
    private static final Pattern EXPECTED_ACTUAL = Pattern.compile(
            "expected:\\s*<?\"?([^>\"\\n]+?)\"?>?\\s*but was:\\s*<?\"?([^>\"\\n]+?)\"?>?\\s*$",
            Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern ASSERTJ =
            Pattern.compile("expected:\\s*\"?(.+?)\"?\\s*\\n\\s*but was:\\s*\"?(.+?)\"?\\s*(\\n|$)");
    static final List<String> VERDICTS = List.of(
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
    private final StateStore store;

    Triage(EngineContext ctx) {
        this.ctx = ctx;
        this.paths = ctx.paths;
        this.config = ctx.config;
        this.store = ctx.store;
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
        if ("naming-conflict".equals(p.type)) {
            msg = switch (letter) {
                case "A" -> {
                    s.sharesDeveloperFile = true;
                    yield "gta will add its tests to " + paths.testSourceRel(item.testClass);
                }
                case "B" -> {
                    st.move(s, ItemStatus.CANCELLED);
                    yield "item cancelled; the developer's test class is left alone";
                }
                case "C" -> "the name is checked again on the next step";
                default ->
                    throw new GtaException(
                            ErrorCode.USAGE_UNPARSEABLE_ANSWER,
                            "Option " + letter + " is not available",
                            "Reply A, B or C");
            };
        } else if ("defect".equals(p.type) && letter.equals("A")) {
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

    Map<String, Object> applyTriage(WorkState st, PlanItem item, WorkState.ItemState s, String verdict, String reason) {
        boolean catching = "catching".equals(item.intent);
        if (catching && "product-defect".equals(verdict) && "fails".equals(s.onBase)) {
            throw new GtaException(
                    ErrorCode.USAGE_INVALID_ARGUMENT,
                    item.id + " fails on the base too, so the change did not cause it: it is not a catch",
                    "Use test-defect, spec-gap or environment; report a pre-existing defect with /gta incident");
        }
        if (catching && "spec-error".equals(verdict)) {
            // a failing catching test may be the catch: a human decides, never the auto-correction
            s.verdict = verdict;
            return ctx.items.needsHuman(st, item, s, "spec-error on a catching test (is the change wrong?): " + reason);
        }
        // a catch candidate goes to a human whatever the oracle source: the human is the one who confirms (plan §6)
        boolean humanBacked =
                catching || "confirmed".equals(item.oracle.source) || "documented".equals(item.oracle.source);
        s.verdict = verdict;
        switch (verdict) {
            case "test-defect", "flaky" -> {
                com.gta.core.state.Lessons.record(paths, verdict, item.capability + " " + item.layer + " test", reason);
                st.move(s, ItemStatus.REPAIRING);
                s.attempts++;
                s.reason =
                        ("flaky".equals(verdict) ? "flaky: only waiting/synchronisation may change. " : "test defect: ")
                                + reason;
                if (s.attempts >= config.budgets.maxAttemptsPerItem) {
                    return ctx.items.needsHuman(st, item, s, "repair attempts exhausted after triage: " + reason);
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
                // a suspected defect is an eval candidate for the model evals (plan §12)
                com.gta.core.state.Lessons.record(paths, "defect", item.ref, reason);
                Map<String, Object> out = ctx.items.needsHuman(st, item, s, "defect: " + reason);
                st.consecutiveFailures = 0; // a suspected product defect is a finding, not an authoring failure
                return out;
            }
            case "spec-error" -> {
                if (!humanBacked) {
                    return autoCorrect(st, item, s, reason);
                }
                return ctx.items.needsHuman(
                        st, item, s, "spec-error on a " + item.oracle.source + " expectation: " + reason);
            }
            default -> {
                return ctx.items.needsHuman(st, item, s, verdict + ": " + reason);
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
            return ctx.items.needsHuman(
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
                revised.scenario(p.ref).ifPresent(sc -> p.scenarioRevision = sc.revision());
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
}
