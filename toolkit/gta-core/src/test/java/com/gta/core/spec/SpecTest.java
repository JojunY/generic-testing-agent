package com.gta.core.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The spec model (plan §3.1). */
class SpecTest {
    final Spec spec = SpecParser.parseText(SpecTestData.CREATE_ORDER);

    @Test
    void shouldReadTheHeader() {
        assertEquals("order.create", spec.flowId());
        assertEquals("draft", spec.status());
        assertEquals("rest-api", spec.capability());
        assertEquals(6, spec.risk());
    }

    @Test
    void shouldFindScenariosAndTheirRequirement() {
        Spec.Scenario s01 = spec.scenario("order.create/s01").orElseThrow();

        assertEquals(2, spec.scenarios().size());
        assertEquals("order.create/r01", spec.requirementOf(s01).orElseThrow().id());
        assertEquals(List.of("201", "50.00"), s01.thenLiterals(), "the expected values in backticks");
        assertFalse(s01.reviewerChecked());
        assertTrue(spec.scenario("order.create/s99").isEmpty());
    }

    @Test
    void shouldTellOpenQuestionsAndTheCurrentCodeOption() {
        Spec.Question q = spec.openQuestions().get(0);

        assertFalse(q.answered());
        assertEquals("high", q.impact());
        assertEquals(List.of("order.create/s02"), q.affects());
        assertTrue(q.currentCodeOption().isPresent(), "option '= current code'");
        assertTrue(q.chosen().isEmpty());
    }

    @Test
    void shouldChangeTheRevisionOnlyWhenThePromiseChanges() {
        Spec.Scenario s01 = spec.scenario("order.create/s01").orElseThrow();
        String before = s01.revision();

        Spec reworded =
                SpecParser.parseText(SpecTestData.CREATE_ORDER.replace("the total is `50.00`", "the total is `55.00`"));

        assertEquals(
                before,
                SpecParser.parseText(SpecTestData.CREATE_ORDER)
                        .scenario("order.create/s01")
                        .orElseThrow()
                        .revision());
        assertFalse(before.equals(
                reworded.scenario("order.create/s01").orElseThrow().revision()));
    }
}
