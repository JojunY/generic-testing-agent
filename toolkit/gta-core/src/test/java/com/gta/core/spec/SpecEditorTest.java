package com.gta.core.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** gtaw's edits of gtaw-owned spec fields (plan §3.2); everything else in the text stays as the analyst wrote it. */
class SpecEditorTest {
    @Test
    void shouldSetHeaderAndElementFields() {
        Spec s = SpecParser.parseText(new SpecEditor(SpecTestData.CREATE_ORDER)
                .setHeader("status", "in-review")
                .setHeader("rev", "R2")
                .setElement("order.create/s02", "source", "confirmed")
                .text());

        assertEquals("in-review", s.status());
        assertEquals("R2", s.rev());
        assertEquals("confirmed", s.scenario("order.create/s02").orElseThrow().source());
    }

    @Test
    void shouldTickAnAnswerAndRecordTheRound() {
        String text = new SpecEditor(SpecTestData.CREATE_ORDER)
                .tick("order.create/q01", "A")
                .setRound("R1")
                .appendRecord(new CommentMeta(
                        "answer", List.of(new CommentMeta.Entry("round", "R1"), new CommentMeta.Entry("text", "1A"))))
                .text();
        Spec s = SpecParser.parseText(text);

        assertTrue(s.questions.get(0).answered());
        assertEquals("A", s.questions.get(0).chosen().orElseThrow().letter);
        assertEquals("R1", s.questionRound);
        assertEquals(1, s.answers.size());
    }

    @Test
    void shouldReplaceAThenValueOnlyInItsScenario() {
        String text = new SpecEditor(SpecTestData.CREATE_ORDER)
                .replaceThenLiteral("order.create/s01", "50.00", "55.00")
                .text();

        assertEquals(
                List.of("201", "55.00"),
                SpecParser.parseText(text)
                        .scenario("order.create/s01")
                        .orElseThrow()
                        .thenLiterals());
    }

    @Test
    void shouldRecordRefHashesOnTheRequirement() {
        String text = new SpecEditor(SpecTestData.CREATE_ORDER)
                .setRefHashes("order.create/r01", Map.of("method:com.acme.order.OrderController#create", "abc123"))
                .text();

        assertTrue(text.contains("abc123"), text);
    }
}
