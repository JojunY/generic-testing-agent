package com.gta.core.report;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.config.GtaPaths;
import com.gta.core.gate.Gate;
import com.gta.core.plan.PlanItem;
import com.gta.core.spec.SpecTestData;
import com.gta.core.state.ItemStatus;
import com.gta.core.state.WorkState;
import com.gta.core.util.AtomicFiles;
import com.gta.core.util.Json;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The session report and PR comment (plan §10.5): gate, traceability, findings and what needs a human. */
class ReporterTest {
    @TempDir
    Path root;

    GtaPaths paths;

    @BeforeEach
    void approvedSpec() {
        paths = new GtaPaths(root);
        AtomicFiles.write(
                paths.spec("order.create"),
                SpecTestData.CREATE_ORDER.replace("status=draft", "status=approved rev=R2")
                        + "<!-- gta: id=order.create/f01 kind=behavior-suspect -->\n- The discount uses > instead of >=.\n");
    }

    private static Gate.Result gate() throws Exception {
        return Json.MAPPER.readValue("""
                {"decision": "fail", "base": "origin/main", "checkedScenarios": 2,
                 "failures": [{"code": "GATE_SCENARIO_REGRESSED", "target": "order.create/s01", "message": "now failing"},
                              {"code": "GATE_UNSTABLE_TEST", "target": "order.create/s02", "message": "flaky", "waived": true}],
                 "concerns": [{"code": "GATE_ASSUMED_CLAIM", "target": "order.create/s02", "message": "assumed"}]}
                """, Gate.Result.class);
    }

    private static WorkState state() {
        WorkState st = new WorkState();
        WorkState.ItemState human = new WorkState.ItemState();
        human.id = "order.create/s01#component-01";
        human.status = ItemStatus.NEEDS_HUMAN;
        human.reason = "repair attempts exhausted";
        WorkState.ItemState defect = new WorkState.ItemState();
        defect.id = "order.create/s02#component-01";
        defect.status = ItemStatus.DEFECT_FOUND;
        st.items.put(human.id, human);
        st.items.put(defect.id, defect);
        st.pendingReview.add("order.create/s01: `50.00` → `55.00`");
        st.newConfirmations.add("order.create R1: \"1B\"");
        return st;
    }

    @Test
    void shouldReportTheGateTraceabilityFindingsAndWhatNeedsAHuman() throws Exception {
        PlanItem item = new PlanItem();
        item.id = "order.create/s01#component-01";
        item.ref = "order.create/s01";
        item.flowId = "order.create";

        String md = new Reporter(paths).markdown(state(), List.of(item), gate(), false);

        for (String expected : List.of(
                "# gta report",
                "**Gate:** ❌ fail (base `origin/main`, 2 locked scenarios checked)",
                "`GATE_SCENARIO_REGRESSED` order.create/s01 — now failing",
                "~~`GATE_UNSTABLE_TEST` order.create/s02 — flaky~~ (waived)",
                "### Concerns",
                "### Spec findings",
                "### Waiting for a human",
                "### Product defects found",
                "### Spec corrections to re-confirm",
                "| order.create | Create an order | order.create/s01 valid order | observed | `order.create/s01#component-01` | needs-human |")) {
            assertTrue(md.contains(expected), "missing '" + expected + "' in:\n" + md);
        }
    }

    @Test
    void shouldRenderThePrCommentWithoutAGate() {
        String md = new Reporter(paths).markdown(new WorkState(), List.of(), null, true);

        assertTrue(md.startsWith("## gta — behaviour & test report"), md);
        assertTrue(
                md.contains(
                        "| order.create | Create an order | order.create/s01 valid order | observed | — | approved |"),
                md);
    }

    @Test
    void shouldCountCatchingHits() {
        PlanItem caught = new PlanItem();
        caught.id = "order.create/s01#component-01";
        caught.intent = "catching";
        PlanItem passed = new PlanItem();
        passed.id = "order.create/s02#component-01";
        passed.intent = "catching";
        WorkState st = new WorkState();
        for (PlanItem i : List.of(caught, passed)) {
            WorkState.ItemState s = new WorkState.ItemState();
            s.id = i.id;
            s.status = i == caught ? ItemStatus.DEFECT_FOUND : ItemStatus.PASSED;
            st.items.put(i.id, s);
        }

        String md = new Reporter(paths).markdown(st, List.of(caught, passed), null, true);

        assertTrue(md.contains("Catching hits: 1 of 2"), md);
        assertTrue(md.contains("- `order.create/s01#component-01`"), md);
    }
}
