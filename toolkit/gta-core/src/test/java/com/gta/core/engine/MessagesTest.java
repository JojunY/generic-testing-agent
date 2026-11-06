package com.gta.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** What people read comes in output.language (plan §5.10); every message exists in both languages. */
class MessagesTest {
    @Test
    void shouldHaveEveryMessageInBothLanguages() {
        assertTrue(Messages.complete());
        assertEquals("[high impact] ", Messages.of("en").t("high"));
        assertEquals("[高影响] ", Messages.of("zh-CN").t("high"));
        assertEquals("[high impact] ", Messages.of("fr").t("high"), "an unknown language falls back to English");
        assertEquals("  ← recommended: the doc says so", Messages.of("en").t("recommended.why", "the doc says so"));
        assertThrows(IllegalArgumentException.class, () -> Messages.of("en").t("no.such.message"));
    }
}
