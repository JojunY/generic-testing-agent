package com.gta.core.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpecParserTest {

    @Test
    void shouldParseHeaderRequirementsScenariosAndQuestions() {
        Spec spec = SpecParser.parseText(SpecTestData.CREATE_ORDER);

        assertEquals("order.create", spec.flowId());
        assertEquals("rest-api", spec.capability());
        assertEquals(6, spec.risk());
        assertEquals("draft", spec.status());
        assertEquals(1, spec.requirements.size());
        assertEquals(2, spec.scenarios().size());
        assertEquals(1, spec.openQuestions().size());
    }

    @Test
    void shouldExtractBacktickThenLiteralsOnly() {
        Spec.Scenario s01 = SpecParser.parseText(SpecTestData.CREATE_ORDER)
                .scenario("order.create/s01")
                .orElseThrow();

        assertEquals(List.of("201", "50.00"), s01.thenLiterals());
        assertEquals("observed", s01.source());
    }

    @Test
    void shouldRecogniseTheCurrentCodeOption() {
        Spec.Question q =
                SpecParser.parseText(SpecTestData.CREATE_ORDER).openQuestions().get(0);

        assertTrue(q.options.get(0).isCurrentCode());
        assertFalse(q.options.get(1).isCurrentCode());
    }

    @Test
    void shouldReadAPlainCommentThatMentionsGtaAsText() {
        String text = SpecTestData.CREATE_ORDER.replace(
                "#### Scenario: valid order\n", "#### Scenario: valid order\n<!-- TODO: ask about gta: retries -->\n");

        Spec spec = SpecParser.parseText(text);

        assertEquals("observed", spec.scenario("order.create/s01").orElseThrow().source());
    }

    @Test
    void shouldRejectAGtaCommentThatIsNeverClosed() {
        GtaException e = assertThrows(
                GtaException.class, () -> SpecParser.parseText(SpecTestData.CREATE_ORDER + "\n<!-- gta: answer q=1\n"));

        assertEquals(ErrorCode.POLICY_SPEC_INVALID, e.code());
    }
}
