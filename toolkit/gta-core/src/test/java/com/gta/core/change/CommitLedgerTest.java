package com.gta.core.change;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.config.GtaPaths;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Only commits gtaw recorded are trusted by the scope check (AGENTS.md). */
class CommitLedgerTest {
    @Test
    void shouldRememberTheCommitsGtawMade(@TempDir Path root) {
        CommitLedger ledger = new CommitLedger(new GtaPaths(root));
        assertFalse(ledger.contains("abc"), "an empty ledger trusts nothing");

        ledger.record("abc");
        ledger.record("def");
        ledger.record("abc");

        assertTrue(ledger.contains("def"));
        assertEquals(Set.of("abc", "def"), new CommitLedger(new GtaPaths(root)).shas(), "kept on disk, no duplicates");
    }
}
