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

    @Test
    void shouldWriteDigitsAsAsciiWhateverTheDefaultLocale() {
        java.util.Locale before = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-SA")); // formats 12 as ١٢ by default
            assertEquals("All 12 flows", Messages.of("en").t("pick.all", 12).substring(0, 12));
        } finally {
            java.util.Locale.setDefault(before);
        }
    }

    @Test
    void shouldNameEveryTriageVerdictInPlainWords() {
        for (String verdict : Triage.VERDICTS) {
            assertTrue(Messages.of("en").has("verdict." + verdict), verdict);
        }
    }
}
