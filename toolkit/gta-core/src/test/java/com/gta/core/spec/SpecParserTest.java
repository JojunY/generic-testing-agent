package com.gta.core.spec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecParserTest {

    @Test
    void shouldParseHeaderRequirementsScenariosAndQuestions() {
        Spec spec = SpecParser.parseText(SpecFixtures.CREATE_ORDER);

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
        Spec.Scenario s01 = SpecParser.parseText(SpecFixtures.CREATE_ORDER).scenario("order.create/s01").orElseThrow();

        assertEquals(java.util.List.of("201", "50.00"), s01.thenLiterals());
        assertEquals("observed", s01.source());
    }

    @Test
    void shouldRecogniseTheCurrentCodeOption() {
        Spec.Question q = SpecParser.parseText(SpecFixtures.CREATE_ORDER).openQuestions().get(0);

        assertTrue(q.options.get(0).isCurrentCode());
        assertFalse(q.options.get(1).isCurrentCode());
    }
}
