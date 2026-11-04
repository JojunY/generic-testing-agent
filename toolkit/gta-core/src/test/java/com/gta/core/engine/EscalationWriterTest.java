package com.gta.core.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.error.ErrorCode;
import com.gta.core.plan.PlanItem;
import com.gta.core.state.ItemStatus;
import com.gta.core.state.WorkState;
import com.gta.core.util.ProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Escalation records are the package a human picks up (plan §5.5): status, attempts, reason and the last failure. */
class EscalationWriterTest {
    @Test
    void shouldWriteAndCommitOneRecordPerItem(@TempDir Path root) throws Exception {
        for (String[] cmd : new String[][] {
            {"git", "init", "-q"}, {"git", "config", "user.email", "t@example.com"}, {"git", "config", "user.name", "t"}
        }) {
            ProcessRunner.run(root, java.util.List.of(cmd));
        }
        PlanItem item = new PlanItem();
        item.id = "order.create/s01#component-01";
        item.flowId = "order.create";
        item.title = "valid order";
        WorkState.ItemState s = new WorkState.ItemState();
        s.id = item.id;
        s.status = ItemStatus.NEEDS_HUMAN;
        s.attempts = 2;
        s.lastError = ItemWorkflow.lastError(s, ErrorCode.TEST_ASSERTION_FAILED, "expected 201 but was 500");

        new EngineContext(root).escalations.writeEscalation(item, s, "repair attempts exhausted");

        String text = Files.readString(root.resolve(".gta/escalations/order.create__s01__component-01.md"));
        assertTrue(text.contains("# Escalation: order.create/s01#component-01"), text);
        assertTrue(text.contains("- Status: `needs-human`") && text.contains("- Attempts: 2"), text);
        assertTrue(text.contains("repair attempts exhausted") && text.contains("TEST_ASSERTION_FAILED"), text);
        assertTrue(ProcessRunner.run(root, ProcessRunner.cmd("git", "log", "-1", "--format=%s"))
                .output()
                .startsWith("docs(gta): escalate"));
    }
}
