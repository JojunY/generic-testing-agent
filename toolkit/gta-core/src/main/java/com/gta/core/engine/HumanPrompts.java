package com.gta.core.engine;

import com.gta.core.config.GtaPaths;
import com.gta.core.plan.PlanItem;
import com.gta.core.spec.Spec;
import com.gta.core.state.WorkState;
import com.gta.core.util.Ids;

import static com.gta.core.engine.NodeResults.firstLines;

/** All human-facing text gtaw shows through the orchestrator: question rounds, approval and decision prompts. */
final class HumanPrompts {
    private final GtaPaths paths;

    HumanPrompts(EngineContext ctx) {
        this.paths = ctx.paths;
    }

    String renderQuestions(Spec spec, String round) {
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
        b.append("Reply like \"").append(round).append(": 1A 2B rest default\". High-impact questions need an explicit answer; ")
                .append("\"rest default\" picks the \"= current code\" option and only produces characterization tests.");
        return b.toString();
    }

    String renderApproval(Spec spec) {
        long confirmed = spec.scenarios().stream().filter(s -> s.source().equals("confirmed") || s.source().equals("documented")).count();
        long observed = spec.scenarios().stream().filter(s -> s.source().equals("observed")).count();
        long defaulted = spec.scenarios().stream().filter(s -> s.source().equals("defaulted")).count();
        StringBuilder b = new StringBuilder();
        b.append("── ").append(spec.flowId()).append("@").append(spec.rev()).append(" ready for approval ──\n");
        b.append("Spec: ").append(paths.rel(spec.path)).append(" — ").append(spec.requirements.size()).append(" requirements, ")
                .append(spec.scenarios().size()).append(" scenarios (confirmed/documented ").append(confirmed).append(", observed ")
                .append(observed).append(", defaulted ").append(defaulted).append(")\n");
        b.append("Observed and defaulted scenarios become characterization tests (they lock current behaviour, not correctness).\n");
        b.append("Reply \"approve ").append(spec.flowId()).append("@").append(spec.rev())
                .append("\" to approve, or say what to change.");
        return b.toString();
    }

    WorkState.Pending humanDecision(WorkState.ItemState s, PlanItem item) {
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
        b.append("Escalation file: ").append(paths.rel(paths.escalations().resolve(Ids.itemKey(item.id) + ".md"))).append("\n");
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
}
