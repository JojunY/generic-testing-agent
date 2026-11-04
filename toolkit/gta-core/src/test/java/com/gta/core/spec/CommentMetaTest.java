package com.gta.core.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The <!-- gta: ... --> comments that carry ids, sources and records in a spec (plan §3.1). */
class CommentMetaTest {
    @Test
    void shouldParseKeysQuotedValuesAndRepeatedKeys() {
        CommentMeta m = CommentMeta.parse(
                        "<!-- gta: id=order.create/r01 ref=method:A#b ref=config:x quote=\"returns 503 after 3s\" -->")
                .orElseThrow();

        assertEquals(Optional.of("order.create/r01"), m.get("id"));
        assertEquals(List.of("method:A#b", "config:x"), m.getAll("ref"));
        assertEquals("returns 503 after 3s", m.getOr("quote", ""));
        assertEquals("-", m.getOr("missing", "-"));
    }

    @Test
    void shouldFindOnlyGtaComments() {
        String text = "<!-- a normal comment -->\n<!-- gta: id=x -->\ntext <!-- gta:answer round=R1 -->";

        assertTrue(CommentMeta.isGtaComment("<!-- gta: id=x -->"));
        assertFalse(CommentMeta.isGtaComment("<!-- note -->"));
        assertEquals(2, CommentMeta.findAll(text).size());
        assertEquals("answer", CommentMeta.findAll(text).get(1).kind());
        assertEquals(Optional.empty(), CommentMeta.parse("<!-- note -->"));
    }

    @Test
    void shouldEditAndFormatRoundTrip() {
        CommentMeta m =
                CommentMeta.parse("<!-- gta: id=s01 source=observed -->").orElseThrow();

        CommentMeta edited = m.with("source", "confirmed").with("by", "R1").without("id");

        assertEquals(Optional.of("confirmed"), edited.get("source"));
        assertEquals(Optional.empty(), edited.get("id"));
        assertEquals(
                edited.entries(),
                CommentMeta.parse(edited.format()).orElseThrow().entries());
        assertEquals(List.of(), m.withEntries(List.of()).entries());
        assertTrue(new CommentMeta("approval", List.of(new CommentMeta.Entry("text", "a b")))
                .format()
                .contains("text=\"a b\""));
    }
}
