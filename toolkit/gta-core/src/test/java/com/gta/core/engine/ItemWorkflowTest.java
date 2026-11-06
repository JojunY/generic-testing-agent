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

    @Test
    void shouldAskThePersonAndBriefTheAuthorAgainWithTheAnswer() throws Exception {
        s.status = ItemStatus.AUTHORING;
        s.token = "t";
        new StateStore(paths).save(st);

        Map<String, Object> asked =
                items().submitQuestion(s.id, "t", "Is the total rounded per line or per order?", "per order");

        assertEquals("answer", asked.get("node"));
        String prompt = String.valueOf(asked.get("prompt"));
        org.junit.jupiter.api.Assertions.assertTrue(prompt.contains("rounded per line or per order"), prompt);
        org.junit.jupiter.api.Assertions.assertTrue(prompt.contains("recommended: per order"), prompt);
        WorkState saved = new StateStore(paths).load();
        assertEquals("question", saved.pending.type);
        assertEquals(ItemStatus.AUTHORING, saved.item(s.id).status, "asking keeps the item with its author");
        assertEquals(
                ErrorCode.STATE_STALE_SESSION,
                assertThrows(GtaException.class, () -> items().submitItem(s.id, "t"))
                        .code(),
                "the brief that asked is spent");

        items().answerQuestion(saved, saved.pending, "OK");

        assertEquals(null, saved.pending);
        assertEquals("per order", saved.item(s.id).questions.get(0).answer, "OK takes the recommendation");
        st = saved;
        Map<String, Object> node = dispatch().node();
        assertEquals("author", node.get("node"));
        String brief = java.nio.file.Files.readString(root.resolve(String.valueOf(node.get("brief"))));
        org.junit.jupiter.api.Assertions.assertTrue(brief.contains("## Answers from the person"), brief);
        org.junit.jupiter.api.Assertions.assertTrue(brief.contains("A: per order") && brief.contains("2 left"), brief);
    }

    @Test
    void shouldTakeAnOwnAnswerVerbatimAndRefuseAnOkWithoutARecommendation() {
        s.status = ItemStatus.REPAIRING;
        s.token = "t";
        new StateStore(paths).save(st);
        items().submitQuestion(s.id, "t", "Which clock does the test use?", " ");
        WorkState saved = new StateStore(paths).load();

        assertEquals(
                ErrorCode.USAGE_UNPARSEABLE_ANSWER,
                assertThrows(GtaException.class, () -> items().answerQuestion(saved, saved.pending, "OK"))
                        .code(),
                "nothing to take");
        assertEquals(
                ErrorCode.USAGE_UNPARSEABLE_ANSWER,
                assertThrows(GtaException.class, () -> items().answerQuestion(saved, saved.pending, "  "))
                        .code());

        items().answerQuestion(saved, saved.pending, "OK, but use a fixed Clock at 2024-01-01");

        assertEquals(
                "OK, but use a fixed Clock at 2024-01-01",
                saved.item(s.id).questions.get(0).answer);
    }

    @Test
    void shouldRefuseAnEmptyQuestionAndMoreThanThreePerItem() {
        s.status = ItemStatus.AUTHORING;
        s.token = "t";
        for (int i = 0; i < 3; i++) {
            s.questions.add(new WorkState.AuthorQuestion());
        }
        new StateStore(paths).save(st);

        assertEquals(
                ErrorCode.USAGE_INVALID_ARGUMENT,
                assertThrows(GtaException.class, () -> items().submitQuestion(s.id, "t", " ", null))
                        .code());
        assertEquals(
                ErrorCode.STATE_BUDGET_EXHAUSTED,
                assertThrows(GtaException.class, () -> items().submitQuestion(s.id, "t", "One more?", null))
                        .code());
    }
}
