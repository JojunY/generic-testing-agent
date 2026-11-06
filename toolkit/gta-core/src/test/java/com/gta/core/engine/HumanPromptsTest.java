package com.gta.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.plan.PlanItem;
import com.gta.core.spec.Spec;
import com.gta.core.spec.SpecParser;
import com.gta.core.spec.SpecTestData;
import com.gta.core.state.WorkState;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The prompts a human sees verbatim (plan §5.5): every option is spelled out, with the reply format. */
class HumanPromptsTest {
    @TempDir
    Path root;

    private Spec spec() {
        Spec s = SpecParser.parseText(SpecTestData.CREATE_ORDER.replace("status=draft", "status=in-review rev=R2"));
        s.path = root.resolve(".gta/specs/order/create.spec.md");
        return s;
    }

    private PlanItem item() {
        PlanItem i = new PlanItem();
        i.id = "order.create/s01#component-01";
        i.flowId = "order.create";
        i.ref = "order.create/s01";
        i.testClass = "com.acme.order.OrderControllerIT";
        return i;
    }

    @Test
    void shouldListTheOpenQuestionsWithTheRecommendedAnswerAndTheReplyFormat() {
        String prompt = new EngineContext(root).prompts.renderQuestions(spec(), "R1");

        assertTrue(prompt.contains("order.create · R1 · 1 question for you"), prompt);
        assertTrue(prompt.contains("1. [high impact] Should an order of exactly 100.00 get the discount?"), prompt);
        assertTrue(prompt.contains("A) No (= current code)\n"), "no pointer on the other option: " + prompt);
        assertTrue(
                prompt.contains("B) Yes → s02 THEN discount `10.00`  ← recommended: the README says \"at least 100\""),
                "the marker becomes a pointer with its reason: " + prompt);
        assertTrue(prompt.contains("Reply OK to take every recommended answer"), prompt);
    }

    @Test
    void shouldAskForAnOkOnTheRevisionItShows() {
        String prompt = new EngineContext(root).prompts.renderApproval(spec());

        assertTrue(prompt.contains("order.create@R2 ready for approval"), prompt);
        assertTrue(prompt.contains("Reply OK to approve order.create@R2"), prompt);
        assertFalse(prompt.contains("observed"), "no internal words for people: " + prompt);
    }

    @Test
    void shouldRecommendAnOptionForEveryDecision() {
        WorkState.ItemState s = new WorkState.ItemState();
        s.reason = "defect: discount uses >";
        HumanPrompts prompts = new EngineContext(root).prompts;

        WorkState.Pending defect = prompts.humanDecision(s, item());
        s.reason = "repair attempts exhausted";
        WorkState.Pending escalation = prompts.humanDecision(s, item());
        WorkState.Pending naming = prompts.namingConflict(item(), "Dana");

        assertEquals("defect", defect.type);
        assertTrue(
                defect.prompt.contains("A) Yes, a defect") && defect.prompt.contains("C) Run it again"), defect.prompt);
        assertEquals("escalation", escalation.type);
        assertTrue(escalation.prompt.contains("B) Cancel this item"), escalation.prompt);
        for (WorkState.Pending p : java.util.List.of(defect, escalation, naming)) {
            assertEquals("A", p.recommended, p.type);
            assertTrue(p.prompt.contains("← recommended: "), p.prompt);
            assertTrue(p.prompt.contains("Reply OK to take the recommendation"), p.prompt);
        }
        assertTrue(naming.prompt.contains("src/test/java/com/acme/order/OrderControllerIT.java"), naming.prompt);
        assertTrue(naming.prompt.contains("last change by Dana"), naming.prompt);
    }

    @Test
    void shouldShowAnAuthorsQuestionWithItsRecommendationWhenThereIsOne() {
        HumanPrompts prompts = new EngineContext(root).prompts;

        WorkState.Pending withRecommendation = prompts.authorQuestion(item(), "Per line or per order?", "per order");
        WorkState.Pending without = prompts.authorQuestion(item(), "Which clock?", null);

        assertEquals("question", withRecommendation.type);
        assertEquals("per order", withRecommendation.recommended);
        assertTrue(withRecommendation.prompt.contains("← recommended: per order"), withRecommendation.prompt);
        assertTrue(
                withRecommendation.prompt.contains("Reply OK to take the recommendation"), withRecommendation.prompt);
        assertTrue(without.prompt.contains("Reply with your answer."), without.prompt);
        assertTrue(!without.prompt.contains("recommended"), without.prompt);
    }

    @Test
    void shouldSpeakChineseWhenTheRepositoryAsksForIt() throws Exception {
        java.nio.file.Files.createDirectories(root.resolve(".gta"));
        java.nio.file.Files.writeString(
                root.resolve(".gta/config.json"), "{ \"output\": { \"language\": \"zh-CN\" } }");
        HumanPrompts prompts = new EngineContext(root).prompts;

        String questions = prompts.renderQuestions(spec(), "R1");
        String approval = prompts.renderApproval(spec());

        assertTrue(questions.contains("需要你回答 1 个问题"), questions);
        assertTrue(questions.contains("A) No (= 代码现状)"), "the marker is shown in the reader's language: " + questions);
        assertTrue(questions.contains("← 推荐："), questions);
        assertTrue(approval.contains("回复 OK 批准 order.create@R2"), approval);
        assertTrue(Messages.complete(), "both catalogues have the same messages");
    }
}
