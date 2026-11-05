package com.gta.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.profile.ServiceProfile;
import com.gta.core.run.TestRunnerFake;
import com.gta.core.spec.SpecParser;
import com.gta.core.state.ItemStatus;
import com.gta.core.state.StateStore;
import com.gta.core.state.WorkState;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Dispatch and the item budgets (plan §5.4). */
class ItemWorkflowTest {
    @TempDir
    Path root;

    GtaPaths paths;
    WorkState st;
    WorkState.ItemState s;

    @BeforeEach
    void service() {
        paths = OrderServiceTestData.create(root);
        OrderServiceTestData.plan(paths, OrderServiceTestData.item());
        st = new WorkState();
        st.session = new WorkState.Session();
        st.session.id = "s1";
        s = new WorkState.ItemState();
        s.id = OrderServiceTestData.ITEM;
        st.items.put(s.id, s);
    }

    private ItemWorkflow items() {
        return new EngineContext(root, new TestRunnerFake()).items;
    }

    private ItemWorkflow.Dispatch dispatch() {
        return items().dispatch(
                        st,
                        SpecParser.parse(paths.spec("order.create")),
                        new ServiceProfile(),
                        List.of(OrderServiceTestData.item()));
    }

    @Test
    void shouldPauseAfterTooManyItemsInARowNeededAHuman() {
        st.consecutiveFailures = 3;

        Map<String, Object> node = dispatch().node();

        assertEquals("escalate", node.get("node"), "budgets.maxConsecutiveFailures is 3");
    }

    @Test
    void shouldEndTheSessionAtItsItemBudget() {
        st.itemsThisSession = 5;

        assertEquals(true, dispatch().sessionOverReason().startsWith("session budget reached"));
    }

    @Test
    void shouldEndTheSessionWhenEveryItemIsDone() {
        s.status = ItemStatus.PASSED;

        assertEquals("all items of order.create are done", dispatch().sessionOverReason());
    }

    @Test
    void shouldReissueABriefWithANewTokenForAnItemBeingEdited() {
        s.status = ItemStatus.REPAIRING;
        s.token = "old";

        Map<String, Object> node = dispatch().node();

        assertEquals("author", node.get("node"));
        assertEquals("s1:0001", node.get("token"), "the old token is invalid now");
    }

    @Test
    void shouldStopChecksAtTheBudgetAndRefuseTheWrongState() {
        s.status = ItemStatus.AUTHORING;
        s.token = "t";
        s.checks = 8;
        new StateStore(paths).save(st);

        assertEquals(
                ErrorCode.STATE_BUDGET_EXHAUSTED,
                assertThrows(GtaException.class, () -> items().check(s.id, "t")).code());
        assertEquals(
                ErrorCode.STATE_ILLEGAL_TRANSITION,
                assertThrows(GtaException.class, () -> items().submitReview(s.id, "t", "accept", ""))
                        .code());
    }

    @Test
    void shouldTellTheAssessorWhatTheBaseRunShowed() {
        WorkState st = new WorkState();
        st.prBase = "origin/main";
        st.prIntent = "Adds a late fee";
        WorkState.ItemState s = new WorkState.ItemState();

        s.onBase = "passes";
        String passes = ItemWorkflow.catchingDetails(st, s);
        org.junit.jupiter.api.Assertions.assertTrue(passes.contains("the change made it fail"), passes);
        org.junit.jupiter.api.Assertions.assertTrue(passes.contains("> Adds a late fee"), "the PR intent: " + passes);
        s.onBase = "fails";
        org.junit.jupiter.api.Assertions.assertTrue(
                ItemWorkflow.catchingDetails(st, s).contains("refuses `product-defect`"));
        s.onBase = "new";
        org.junit.jupiter.api.Assertions.assertTrue(
                ItemWorkflow.catchingDetails(st, s).contains("new on this branch"));
        s.onBase = null;
        org.junit.jupiter.api.Assertions.assertTrue(
                ItemWorkflow.catchingDetails(st, s).contains("could not run it on `origin/main`"));
    }
}
