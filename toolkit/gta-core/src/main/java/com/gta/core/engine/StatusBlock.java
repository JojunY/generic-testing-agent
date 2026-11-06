package com.gta.core.engine;

import com.gta.core.plan.PlanItem;
import com.gta.core.spec.SpecParser;
import com.gta.core.state.ItemStatus;
import com.gta.core.state.WorkState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The status block (plan §5.10): the one thing people read. Five parts, always in this order: state (with progress),
 * what happened, what was checked, what needs the person, and what comes next. gtaw writes it from recorded facts;
 * the orchestrator shows it verbatim.
 */
final class StatusBlock {
    static final int MAX_LINES = 5;

    private final EngineContext ctx;
    private final Messages m;

    StatusBlock(EngineContext ctx) {
        this.ctx = ctx;
        this.m = Messages.of(ctx.config.output.language);
    }

    /**
     * @param needsYou what the person must answer, already in their language; null when nothing waits for them
     * @param next     the next step
     * @param state    one of the {@code state.*} message keys
     */
    String render(WorkState st, String state, String needsYou, String next) {
        StringBuilder b = new StringBuilder();
        String flow = st.activeFlow == null ? "" : " · " + st.activeFlow;
        b.append("── gta").append(flow).append(" ── ").append(m.t(state)).append('\n');
        if (st.activeFlow != null) {
            b.append(progress(st)).append('\n');
        }
        List<WorkState.Event> fresh = st.events.subList(Math.min(st.eventsShown, st.events.size()), st.events.size());
        section(b, "section.happened", fresh.stream().filter(e -> !e.check).toList(), "");
        section(b, "section.checked", fresh.stream().filter(e -> e.check).toList(), "✓ ");
        b.append('\n').append(m.t("section.needs")).append('\n');
        if (needsYou == null || needsYou.isBlank()) {
            b.append("  ").append(m.t("needs.nothing")).append('\n');
        } else {
            needsYou.lines().forEach(l -> b.append("  ").append(l).append('\n'));
        }
        b.append('\n').append(m.t("next", next));
        st.eventsShown = st.events.size();
        return b.toString();
    }

    private void section(StringBuilder b, String title, List<WorkState.Event> events, String bullet) {
        if (events.isEmpty()) {
            return;
        }
        b.append('\n').append(m.t(title)).append('\n');
        List<WorkState.Event> last = events.subList(Math.max(0, events.size() - MAX_LINES), events.size());
        for (int i = 0; i < last.size(); i++) {
            b.append("  ")
                    .append(bullet.isEmpty() ? (i + 1) + ". " : bullet)
                    .append(line(last.get(i)))
                    .append('\n');
        }
    }

    String line(WorkState.Event e) {
        Object[] args = e.args.toArray();
        if ("ev.triage".equals(e.key) && args.length > 1 && m.has("verdict." + args[1])) {
            args[1] = m.t("verdict." + args[1]);
        }
        return m.t(e.key, args);
    }

    /** Spec state ▸ tests passed/total ▸ defects found, for the active flow. */
    String progress(WorkState st) {
        Path spec = ctx.paths.spec(st.activeFlow);
        String status = Files.exists(spec) ? SpecParser.parse(spec).status() : null;
        String specText = status == null ? m.t("spec.none") : m.has("spec." + status) ? m.t("spec." + status) : status;
        List<PlanItem> items = ctx.store.peekPlan().stream()
                .filter(i -> st.activeFlow.equals(i.flowId) && !i.isFoundation())
                .toList();
        long passed = count(st, items, ItemStatus.PASSED);
        long defects = count(st, items, ItemStatus.DEFECT_FOUND);
        return m.t("progress", specText, passed, items.size(), defects);
    }

    private static long count(WorkState st, List<PlanItem> items, ItemStatus status) {
        return items.stream()
                .filter(i -> st.items.containsKey(i.id) && st.items.get(i.id).status == status)
                .count();
    }

    /** The flow candidates as a decision with the riskiest flow without an approved spec recommended. */
    String pick(List<Map<String, Object>> candidates) {
        StringBuilder b = new StringBuilder(m.t("pick.title")).append('\n');
        String recommended = recommendedFlow(candidates);
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> c = candidates.get(i);
            String spec = String.valueOf(c.get("spec"));
            b.append(m.t(
                    "pick.item",
                    i + 1,
                    c.get("flowId"),
                    ((Number) c.get("risk")).intValue(),
                    "none".equals(spec) ? m.t("spec.none") : m.has("spec." + spec) ? m.t("spec." + spec) : spec));
            if (c.get("flowId").equals(recommended)) {
                b.append(m.t("recommended"));
            }
            b.append('\n');
        }
        return b.append(m.t("pick.reply")).toString();
    }

    /** The riskiest candidate that is not approved yet; candidates come riskiest first. */
    static String recommendedFlow(List<Map<String, Object>> candidates) {
        return candidates.stream()
                .filter(c -> !"approved".equals(c.get("spec")))
                .map(c -> String.valueOf(c.get("flowId")))
                .findFirst()
                .orElse(
                        candidates.isEmpty()
                                ? null
                                : String.valueOf(candidates.get(0).get("flowId")));
    }

    Messages messages() {
        return m;
    }
}
