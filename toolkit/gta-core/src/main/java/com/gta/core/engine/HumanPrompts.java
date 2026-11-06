package com.gta.core.engine;

import static com.gta.core.engine.NodeResults.firstLines;

import com.gta.core.config.GtaPaths;
import com.gta.core.plan.PlanItem;
import com.gta.core.spec.Spec;
import com.gta.core.state.WorkState;
import com.gta.core.util.Ids;
import java.util.List;
import java.util.regex.Pattern;

/** All human-facing text gtaw shows through the orchestrator: question rounds, approval and decision prompts. */
final class HumanPrompts {
    private final GtaPaths paths;
    private final Messages m;

    HumanPrompts(EngineContext ctx) {
        this.paths = ctx.paths;
        this.m = Messages.of(ctx.config.output.language);
    }

    /** The question round, every option spelled out, the recommended one marked (plan §3.5, §5.10). */
    String renderQuestions(Spec spec, String round) {
        StringBuilder b = new StringBuilder();
        List<Spec.Question> open = spec.openQuestions();
        String count = open.size() == 1 ? m.t("questions.count.one") : m.t("questions.count.many", open.size());
        b.append(m.t("questions.title", spec.flowId(), round, count)).append('\n');
        b.append(m.t("spec", paths.rel(spec.path))).append("\n\n");
        for (Spec.Question q : open) {
            b.append(q.number)
                    .append(". ")
                    .append("high".equals(q.impact()) ? m.t("high") : "")
                    .append(q.text.replaceFirst("^\\[[^]]*]\\s*", ""))
                    .append('\n');
            for (Spec.Option o : q.options) {
                b.append("     ")
                        .append(o.letter)
                        .append(") ")
                        .append(option(o))
                        .append('\n');
            }
        }
        if (!spec.findings.isEmpty()) {
            b.append(m.t("findings")).append('\n');
            spec.findings.forEach(f -> b.append("  - ")
                    .append(f.meta.getOr("kind", "finding"))
                    .append(": ")
                    .append(f.text)
                    .append('\n'));
        }
        b.append('\n').append(m.t("questions.reply"));
        return b.toString();
    }

    private static final Pattern RECOMMENDED_MARK =
            Pattern.compile("\\s*[(（]\\s*(?i:recommended|推荐|推薦)\\s*(?:[:：]\\s*([^)）]*))?[)）]\\s*");

    /** The option's text with its {@code (recommended: why)} marker moved to the end as a pointer. */
    String option(Spec.Option o) {
        java.util.regex.Matcher r = RECOMMENDED_MARK.matcher(o.text);
        String text = o.text;
        String pointer = "";
        if (r.find()) {
            String why = r.group(1) == null ? "" : r.group(1).strip();
            text = (o.text.substring(0, r.start()) + " " + o.text.substring(r.end())).strip();
            pointer = why.isEmpty() ? m.t("recommended") : m.t("recommended.why", why);
        }
        text = text.replace("= current code", m.t("current.code"));
        return text + pointer;
    }

    String renderApproval(Spec spec) {
        long confirmed = spec.scenarios().stream()
                .filter(s -> s.source().equals("confirmed") || s.source().equals("documented"))
                .count();
        long observed = spec.scenarios().stream()
                .filter(s -> s.source().equals("observed"))
                .count();
        long defaulted = spec.scenarios().stream()
                .filter(s -> s.source().equals("defaulted"))
                .count();
        StringBuilder b = new StringBuilder();
        b.append(m.t("approval.title", spec.flowId(), spec.rev())).append('\n');
        b.append(m.t(
                        "approval.summary",
                        paths.rel(spec.path),
                        spec.requirements.size(),
                        spec.scenarios().size()))
                .append('\n');
        b.append(m.t("approval.confirmed", confirmed)).append('\n');
        if (observed + defaulted > 0) {
            b.append(m.t("approval.locked", observed, defaulted)).append('\n');
        }
        b.append(m.t("approval.reply", spec.flowId(), spec.rev()));
        return b.toString();
    }

    WorkState.Pending humanDecision(WorkState.ItemState s, PlanItem item) {
        WorkState.Pending p = new WorkState.Pending();
        p.itemId = item.id;
        p.flowId = item.flowId;
        boolean defect = s.reason != null && s.reason.startsWith("defect:");
        p.type = defect ? "defect" : "escalation";
        p.recommended = "A";
        StringBuilder b = new StringBuilder();
        b.append(m.t(defect ? "defect.title" : "human.title", item.id)).append('\n');
        b.append(s.reason == null ? "" : s.reason.replaceFirst("^defect:\\s*", ""))
                .append('\n');
        if (s.lastError != null && s.lastError.message != null) {
            b.append(m.t("last.failure", s.lastError.code, firstLines(s.lastError.message, 6)))
                    .append('\n');
        }
        b.append(m.t("escalation.file", paths.rel(paths.escalations().resolve(Ids.itemKey(item.id) + ".md"))))
                .append("\n\n");
        if (defect) {
            b.append("  ")
                    .append(m.t("defect.a"))
                    .append(m.t("recommended.why", m.t("defect.why")))
                    .append('\n');
            b.append("  ").append(m.t("defect.b")).append('\n');
            b.append("  ").append(m.t("defect.c")).append('\n');
        } else {
            b.append("  ")
                    .append(m.t("escalation.a"))
                    .append(m.t("recommended.why", m.t("escalation.why")))
                    .append('\n');
            b.append("  ").append(m.t("escalation.b")).append('\n');
        }
        b.append(m.t("decision.reply"));
        p.prompt = b.toString();
        return p;
    }

    /** Decision when the test class gta would write exists and is not a gta test (plan §9.3, rule E5). */
    WorkState.Pending namingConflict(PlanItem item, String lastAuthor) {
        WorkState.Pending p = new WorkState.Pending();
        p.type = "naming-conflict";
        p.itemId = item.id;
        p.flowId = item.flowId;
        p.recommended = "A";
        String file = paths.testSourceRel(item.testClass);
        p.prompt = m.t("naming.title", item.id) + "\n"
                + m.t("naming.body", file, lastAuthor) + "\n"
                + "  " + m.t("naming.a") + m.t("recommended.why", m.t("naming.why")) + "\n"
                + "  " + m.t("naming.b") + "\n"
                + "  " + m.t("naming.c") + "\n"
                + m.t("decision.reply");
        return p;
    }
}
