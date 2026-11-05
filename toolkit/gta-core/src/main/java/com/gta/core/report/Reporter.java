package com.gta.core.report;

import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.gate.Gate;
import com.gta.core.oracle.OracleShard;
import com.gta.core.plan.PlanItem;
import com.gta.core.spec.Spec;
import com.gta.core.spec.SpecParser;
import com.gta.core.state.WorkState;
import com.gta.core.util.Ids;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Markdown report and PR comment (plan §6, §11.2). Traceability: requirement → scenario → item → status. */
public final class Reporter {
    private final GtaPaths paths;

    public Reporter(GtaPaths paths) {
        this.paths = paths;
    }

    public String markdown(WorkState state, List<PlanItem> plan, Gate.Result gate, boolean prComment) {
        StringBuilder b = new StringBuilder();
        b.append(prComment ? "## gta — behaviour & test report\n\n" : "# gta report\n\n");
        if (gate != null) {
            String icon = switch (gate.decision) {
                case "pass" -> "✅";
                case "concerns" -> "⚠️";
                case "waived" -> "🟡";
                default -> "❌";
            };
            b.append("**Gate:** ")
                    .append(icon)
                    .append(' ')
                    .append(gate.decision)
                    .append(" (base `")
                    .append(gate.base)
                    .append("`, ")
                    .append(gate.checkedScenarios)
                    .append(" locked scenarios checked)\n\n");
            if (!gate.failures.isEmpty()) {
                b.append("### Failures\n\n");
                gate.failures.forEach(c -> b.append("- ")
                        .append(c.waived ? "~~" : "")
                        .append('`')
                        .append(c.code)
                        .append("` ")
                        .append(c.target)
                        .append(" — ")
                        .append(c.message)
                        .append(c.waived ? "~~ (waived)" : "")
                        .append('\n'));
                b.append('\n');
            }
            List<Gate.Condition> confirmations = gate.concerns.stream()
                    .filter(c -> c.code.equals(ErrorCode.GATE_NEW_CONFIRMATION.name())
                            || c.code.equals(ErrorCode.GATE_RELOCKED.name()))
                    .toList();
            if (!confirmations.isEmpty()) {
                b.append("### Human confirmations, approvals and relocks in this change (review them)\n\n");
                confirmations.forEach(c -> b.append("- ")
                        .append(c.target)
                        .append(": ")
                        .append(c.message)
                        .append('\n'));
                b.append('\n');
            }
            List<Gate.Condition> other = gate.concerns.stream()
                    .filter(c -> !confirmations.contains(c))
                    .toList();
            if (!other.isEmpty()) {
                b.append("### Concerns\n\n");
                other.forEach(c -> b.append("- `")
                        .append(c.code)
                        .append("` ")
                        .append(c.target)
                        .append(" — ")
                        .append(c.message)
                        .append('\n'));
                b.append('\n');
            }
        }
        b.append(
                "### Traceability\n\n| Flow | Requirement | Scenario | Source | Item | Status |\n|---|---|---|---|---|---|\n");
        for (Spec spec : specs()) {
            for (Spec.Requirement r : spec.requirements) {
                for (Spec.Scenario s : r.scenarios) {
                    List<PlanItem> items = plan.stream()
                            .filter(i -> s.id() != null && s.id().equals(i.ref))
                            .toList();
                    if (items.isEmpty()) {
                        // no working state (e.g. in CI): fall back to the committed oracle shard
                        String locked = OracleShard.read(paths.oracleShard(spec.flowId())).keySet().stream()
                                .filter(k -> s.id() != null && k.startsWith(s.id() + "#"))
                                .findFirst()
                                .orElse(null);
                        b.append("| ")
                                .append(spec.flowId())
                                .append(" | ")
                                .append(r.title)
                                .append(" | ")
                                .append(s.id())
                                .append(' ')
                                .append(s.title)
                                .append(" | ")
                                .append(s.source())
                                .append(" | ")
                                .append(locked == null ? "—" : "`" + locked + "`")
                                .append(" | ")
                                .append(locked == null ? spec.status() : "locked")
                                .append(" |\n");
                    }
                    for (PlanItem i : items) {
                        WorkState.ItemState st = state.items.get(i.id);
                        b.append("| ")
                                .append(spec.flowId())
                                .append(" | ")
                                .append(r.title)
                                .append(" | ")
                                .append(s.id())
                                .append(' ')
                                .append(s.title)
                                .append(" | ")
                                .append(s.source())
                                .append(" | `")
                                .append(i.id)
                                .append("` | ")
                                .append(st == null ? "pending" : st.status.id())
                                .append(" |\n");
                    }
                }
            }
        }
        b.append('\n');
        List<String> findings = new ArrayList<>();
        for (Spec spec : specs()) {
            spec.findings.forEach(
                    f -> findings.add(spec.flowId() + " `" + f.meta.getOr("kind", "finding") + "`: " + f.text));
        }
        if (!findings.isEmpty()) {
            b.append("### Spec findings\n\n");
            findings.forEach(f -> b.append("- ").append(f).append('\n'));
            b.append('\n');
        }
        List<String> escalations = state.items.values().stream()
                .filter(i -> i.status.id().equals("needs-human"))
                .map(i -> "`" + i.id + "` — " + (i.reason == null ? "" : i.reason))
                .toList();
        if (!escalations.isEmpty()) {
            b.append("### Waiting for a human\n\n");
            escalations.forEach(e -> b.append("- ").append(e).append('\n'));
            b.append('\n');
        }
        List<String> defects = state.items.values().stream()
                .filter(i -> i.status.id().equals("defect-found"))
                .map(i -> "`" + i.id + "` — pinned with `@KnownDefect`; details in `.gta/escalations/"
                        + Ids.itemKey(i.id) + ".md`")
                .toList();
        if (!defects.isEmpty()) {
            b.append("### Product defects found\n\n");
            defects.forEach(d -> b.append("- ").append(d).append('\n'));
            b.append('\n');
        }
        if (!state.pendingReview.isEmpty()) {
            b.append("### Spec corrections to re-confirm (observed values corrected from actual behaviour)\n\n");
            state.pendingReview.forEach(p -> b.append("- ").append(p).append('\n'));
            b.append('\n');
        }
        b.append(
                "_Generated by gtaw. Expected values come from approved specs; `characterization` tests lock current behaviour only._\n");
        return b.toString();
    }

    private List<Spec> specs() {
        List<Spec> out = new ArrayList<>();
        if (!Files.isDirectory(paths.specs())) {
            return out;
        }
        try (Stream<Path> files = Files.walk(paths.specs())) {
            files.filter(p -> p.toString().endsWith(".spec.md")).sorted().forEach(p -> out.add(SpecParser.parse(p)));
        } catch (IOException e) {
            // no specs
        }
        return out;
    }
}
