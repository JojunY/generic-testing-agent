package com.gta.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void shouldListTheOpenQuestionsWithTheirOptionsAndTheReplyFormat() {
        String prompt = new EngineContext(root).prompts.renderQuestions(spec(), "R1");

        assertTrue(prompt.contains("order.create R1 questions"), prompt);
        assertTrue(prompt.contains("1. [high] Should an order of exactly 100.00 get the discount?"), prompt);
        assertTrue(prompt.contains("A) No (= current code)"), prompt);
        assertTrue(prompt.contains("Reply like \"R1: 1A 2B rest default\""), prompt);
    }

    @Test
    void shouldAskForTheExactApprovalReply() {
        String prompt = new EngineContext(root).prompts.renderApproval(spec());

        assertTrue(prompt.contains("order.create@R2 ready for approval"), prompt);
        assertTrue(prompt.contains("approve order.create@R2"), prompt);
    }

    @Test
    void shouldOfferDefectAndEscalationChoices() {
        WorkState.ItemState s = new WorkState.ItemState();
        s.reason = "defect: discount uses >";
        HumanPrompts prompts = new EngineContext(root).prompts;

        WorkState.Pending defect = prompts.humanDecision(s, item());
        s.reason = "repair attempts exhausted";
        WorkState.Pending escalation = prompts.humanDecision(s, item());

        assertEquals("defect", defect.type);
        assertTrue(
                defect.prompt.contains("A) Confirm it is a defect") && defect.prompt.contains("C) Retry"),
                defect.prompt);
        assertEquals("escalation", escalation.type);
        assertTrue(escalation.prompt.contains("B) Cancel this item"), escalation.prompt);
    }

    @Test
    void shouldExplainANamingConflictWithoutADefault() {
        WorkState.Pending p = new EngineContext(root).prompts.namingConflict(item(), "Dana");

        assertEquals("naming-conflict", p.type);
        assertTrue(p.prompt.contains("src/test/java/com/acme/order/OrderControllerIT.java"), p.prompt);
        assertTrue(p.prompt.contains("last change by Dana"), p.prompt);
        assertTrue(p.prompt.contains("A)") && p.prompt.contains("B)") && p.prompt.contains("C)"), p.prompt);
    }
}
