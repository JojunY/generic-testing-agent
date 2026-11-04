package com.gta.core.engine;

import com.gta.core.config.GtaPaths;
import com.gta.core.plan.PlanItem;
import com.gta.core.state.ItemStatus;
import com.gta.core.state.WorkState;
import com.gta.core.util.AtomicFiles;
import com.gta.core.util.Ids;

import java.nio.file.Path;
import java.util.List;

import static com.gta.core.engine.NodeResults.firstLines;

/** Writes and commits {@code .gta/escalations/<item>.md}, the durable record of everything that needed a human. */
final class EscalationWriter {
    /** The line that marks a human-confirmed, pinned defect; ItemWorkflow.confirmedPin looks for it in the committed record. */
    static final String DEFECT_FOUND_MARKER = "- Status: `defect-found`";

    private final EngineContext ctx;
    private final GtaPaths paths;

    EscalationWriter(EngineContext ctx) {
        this.ctx = ctx;
        this.paths = ctx.paths;
    }

    void writeEscalation(PlanItem item, WorkState.ItemState s, String reason) {
        Path f = paths.escalations().resolve(Ids.itemKey(item.id) + ".md");
        StringBuilder b = new StringBuilder();
        b.append("# Escalation: ").append(item.id).append("\n\n- Flow: `").append(item.flowId).append("`\n- Scenario: ")
                .append(item.title).append("\n- Status: `").append(s.status.id()).append("`\n- Attempts: ").append(s.attempts)
                .append("\n- Reason: ").append(reason).append('\n');
        if (s.lastError != null) {
            b.append("- Last failure: `").append(s.lastError.code).append("`\n\n```\n").append(firstLines(s.lastError.message, 20)).append("\n```\n");
        }
        b.append("\n## Suggested next step\n\n");
        if (s.status == ItemStatus.DEFECT_FOUND) {
            b.append("File an issue with the scenario, the expected and the actual values. The test is pinned with `@KnownDefect`:"
                    + " the build stays green while the defect exists, and the test fails with \"known defect now passes\" once the"
                    + " code is fixed - then remove the annotation and run `/gta`.\n");
        } else if (reason.startsWith("Confirmed")) {
            b.append("File an issue with the scenario, the expected and the actual values. The next `/gta` run pins the test with `@KnownDefect`.\n");
        } else if (reason.startsWith("defect:")) {
            b.append("Confirm or reject the defect in chat (`/gta`).\n");
        } else {
            b.append("Look at the last failure and the brief, fix the cause (environment, spec or test), then answer 'A' to retry in `/gta`.\n");
        }
        AtomicFiles.write(f, b.toString());
        String verb = s.status == ItemStatus.DEFECT_FOUND ? "record known defect " : "escalate ";
        ctx.commit(List.of(paths.rel(f)), "docs(gta): " + verb + item.id + "\n\nGta-Item: " + item.id);
    }
}
