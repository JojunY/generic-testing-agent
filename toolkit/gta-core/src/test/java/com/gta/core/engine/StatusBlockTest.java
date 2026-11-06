package com.gta.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.plan.PlanItem;
import com.gta.core.state.ItemStatus;
import com.gta.core.state.StateStore;
import com.gta.core.state.WorkState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The status block (plan §5.10): five fixed parts from recorded facts, in the reader's language. */
class StatusBlockTest {
    @TempDir
    Path root;

    private WorkState state() {
        WorkState st = new WorkState();
        st.activeFlow = "order.create";
        for (String[] item : new String[][] {
            {"order.create/s01#component-01", "PASSED"}, {"order.create/s02#component-01", "DEFECT_FOUND"}
        }) {
            WorkState.ItemState s = new WorkState.ItemState();
            s.id = item[0];
            s.status = ItemStatus.valueOf(item[1]);
            st.items.put(s.id, s);
        }
        return st;
    }

    private void plan() {
        PlanItem foundation = new PlanItem();
        foundation.id = "order#foundation-01";
        foundation.kind = "foundation";
        foundation.flowId = "order.create";
        List<PlanItem> items = new java.util.ArrayList<>(List.of(foundation));
        for (String id : List.of(
                "order.create/s01#component-01", "order.create/s02#component-01", "order.create/s03#component-01")) {
            PlanItem i = new PlanItem();
            i.id = id;
            i.kind = "test";
            i.flowId = "order.create";
            items.add(i);
        }
        new StateStore(new com.gta.core.config.GtaPaths(root)).savePlan(items);
    }

    @Test
    void shouldShowTheFivePartsFromRecordedFactsAndOnlyOnce() {
        plan();
        WorkState st = state();
        st.event(false, "ev.test.failed", "order.create/s02", "expected: <409> but was: <201>");
        st.event(true, "chk.reruns", "order.create/s02");
        st.event(false, "ev.triage", "order.create/s02", "product-defect", "the stock is never checked");
        StatusBlock block = new StatusBlock(new EngineContext(root));

        String text = block.render(
                st,
                "state.waiting",
                "1. Is this a defect?\n   A) Yes  ← recommended",
                "after your reply, gta continues");

        assertTrue(text.startsWith("── gta · order.create ── waiting for you"), text);
        assertTrue(
                text.contains("Progress: spec not written yet ▸ tests 1/3 passed ▸ 1 defects found"),
                "the foundation is not a test: " + text);
        assertTrue(
                text.contains("What happened\n  1. order.create/s02: test failed: expected: <409> but was: <201>"),
                text);
        assertTrue(
                text.contains("  2. order.create/s02: the reviewer says a product defect: the stock is never checked"),
                text);
        assertTrue(text.contains("Checked\n  ✓ order.create/s02: gtaw re-ran it twice with the same result"), text);
        assertTrue(text.contains("Needs you\n  1. Is this a defect?"), text);
        assertTrue(text.endsWith("Next: after your reply, gta continues"), text);
        assertTrue(
                text.indexOf("What happened") < text.indexOf("Checked")
                        && text.indexOf("Checked") < text.indexOf("Needs you"),
                "the parts keep their order");

        String again = block.render(st, "state.continuing", null, "gta continues");
        assertFalse(again.contains("What happened"), "what was shown is not repeated: " + again);
        assertTrue(again.contains("Needs you\n  nothing, continuing"), again);
    }

    @Test
    void shouldKeepTheLastFiveLinesAndSpeakChinese() throws Exception {
        Files.createDirectories(root.resolve(".gta"));
        Files.writeString(root.resolve(".gta/config.json"), "{ \"output\": { \"language\": \"zh-CN\" } }");
        WorkState st = state();
        for (int i = 1; i <= 7; i++) {
            st.event(false, "ev.test.passed", "order.create/s0" + i);
        }

        String text = new StatusBlock(new EngineContext(root)).render(st, "state.done", null, "完成");

        assertTrue(text.contains("本次完成"), text);
        assertTrue(text.contains("刚才发生了什么"), text);
        assertFalse(text.contains("order.create/s02："), "only the last 5 lines: " + text);
        assertTrue(text.contains("5. order.create/s07：测试通过并已提交"), text);
        assertTrue(text.contains("需要你\n  无，继续中"), text);
    }

    @Test
    void shouldRecommendTheRiskiestFlowThatIsNotApprovedYet() {
        List<Map<String, Object>> candidates = List.of(
                Map.of("flowId", "order.create", "risk", 6, "spec", "approved"),
                Map.of("flowId", "order.cancel", "risk", 6, "spec", "none"),
                Map.of("flowId", "order.get", "risk", 2, "spec", "none"));

        assertEquals("order.cancel", StatusBlock.recommendedFlow(candidates));
        String pick = new StatusBlock(new EngineContext(root)).pick(candidates);
        assertTrue(pick.contains("2. order.cancel (risk 6, spec: not written yet)  ← recommended"), pick);
        assertTrue(pick.contains("Reply OK for the recommended flow"), pick);
    }
}
