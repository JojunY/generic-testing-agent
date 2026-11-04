package com.gta.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.spec.Spec;
import com.gta.core.spec.SpecParser;
import com.gta.core.spec.SpecTestData;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The test case ledger (plan §10.6): an index of the spec's scenarios, never a copy of their steps. */
class CaseLedgerTest {
    private static Spec spec() {
        return SpecParser.parseText(SpecTestData.CREATE_ORDER);
    }

    private static CaseLedger synced() {
        CaseLedger l = new CaseLedger("order.create");
        l.syncWithSpec(spec(), "create.spec.md", Map.of());
        return l;
    }

    @Test
    void shouldAddOneRowPerScenarioOfTheSpec() {
        CaseLedger l = synced();

        assertEquals(
                List.of("order.create/s01", "order.create/s02"),
                l.rows().stream().map(r -> r.id).toList());
        CaseLedger.Row s01 = l.row("order.create/s01").orElseThrow();
        assertEquals("valid order", s01.title);
        assertEquals("create.spec.md#s01", s01.linkedSpec);
        assertEquals(CaseLedger.NOT_RUN, s01.result);
        assertEquals(CaseLedger.ACTIVE, s01.status);
        assertEquals(CaseLedger.NOT_AUTOMATED, s01.automation);
    }

    @Test
    void shouldSummariseTheClausesInsteadOfCopyingThem() {
        CaseLedger.Row s01 = synced().row("order.create/s01").orElseThrow();

        assertEquals("customer `c-1` posts subtotal `50.00`", s01.content);
        assertEquals("the response status is `201` (+1 more)", s01.expect, "only the first THEN, and how many follow");
        assertEquals("a".repeat(99) + "…", CaseLedger.summary(List.of("a".repeat(150))));
        assertEquals("", CaseLedger.summary(List.of()));
    }

    @Test
    void shouldTakeThePriorityFromTheScenarioOrDefaultToP1() {
        Spec withPriority = SpecParser.parseText(SpecTestData.CREATE_ORDER.replace(
                "source=observed hint.layer", "source=observed priority=p0 hint.layer"));
        CaseLedger l = new CaseLedger("order.create");

        l.syncWithSpec(withPriority, "create.spec.md", Map.of());

        assertEquals("P0", l.row("order.create/s01").orElseThrow().priority);
        assertEquals("P1", l.row("order.create/s02").orElseThrow().priority, "no priority given");
    }

    @Test
    void shouldMarkACaseForReviewWhenItsLockedTestIsForAnotherRevision() {
        CaseLedger l = new CaseLedger("order.create");

        l.syncWithSpec(spec(), "create.spec.md", Map.of("order.create/s01", "an-older-revision"));

        assertEquals(CaseLedger.NEEDS_REVIEW, l.row("order.create/s01").orElseThrow().status);
        assertEquals(CaseLedger.ACTIVE, l.row("order.create/s02").orElseThrow().status);
    }

    @Test
    void shouldRetireACaseThatLeftTheSpecAndKeepItRetired() {
        CaseLedger l = synced();
        Spec without = SpecParser.parseText(
                SpecTestData.CREATE_ORDER.replaceAll("(?s)#### Scenario: order at the threshold.*?(?=\n## )", ""));

        l.syncWithSpec(without, "create.spec.md", Map.of());

        assertEquals(
                CaseLedger.RETIRED,
                l.row("order.create/s02").orElseThrow().status,
                "its test keeps running until confirmed");
        l.syncWithSpec(spec(), "create.spec.md", Map.of());
        assertEquals(CaseLedger.RETIRED, l.row("order.create/s02").orElseThrow().status, "only a human brings it back");
    }

    @Test
    void shouldRecordTheLatestResultWithItsDateAndVersion() {
        CaseLedger l = synced();

        l.recordResult(
                "order.create/s01",
                "com.acme.OrderControllerIT#shouldCreate",
                CaseLedger.result(true, "2026-11-04", "a1b2c3d"),
                CaseLedger.ACTIVE);
        l.recordResult("order#foundation", "x", "Pass", CaseLedger.ACTIVE);

        CaseLedger.Row s01 = l.row("order.create/s01").orElseThrow();
        assertEquals("Pass 2026-11-04 a1b2c3d", s01.result);
        assertEquals("com.acme.OrderControllerIT#shouldCreate", s01.automation);
        assertEquals(2, l.rows().size(), "a test outside the spec adds no row");
        assertEquals("Fail 2026-11-04 a1b2c3d", CaseLedger.result(false, "2026-11-04", "a1b2c3d"));
    }

    @Test
    void shouldReportEveryRowChange() {
        CaseLedger l = synced();
        assertEquals(2, l.changes().size(), "two new rows");

        CaseLedger reread = CaseLedger.parse(l.format(), "order.create");
        reread.recordResult("order.create/s01", null, "Pass 2026-11-04 a1b2c3d", null);
        reread.recordResult("order.create/s02", null, CaseLedger.NOT_RUN, null);

        assertEquals(
                List.of(new CaseLedger.Change(
                        "order.create/s01", "Active, Not run", "Active, Pass 2026-11-04 a1b2c3d")),
                reread.changes(),
                "unchanged rows are not reported");
    }

    @Test
    void shouldKeepAHumansStatusEditAndTheManualCasesWhenRewritten(@TempDir Path dir) {
        Path file = dir.resolve("create.cases.md");
        synced().write(file);
        String edited = CaseLedger.read(file, "order.create")
                .format()
                .replace(
                        "| P1 | Not run | Active |\n| order.create/s02",
                        "| P1 | Not run | Retired |\n| order.create/s02")
                .replace(
                        "| Status |\n|---|---|---|---|---|---|---|\n",
                        "| Status |\n|---|---|---|---|---|---|---|\n| order.create/m01 | Exploratory: odd currencies | Pay in JPY \\| no decimals | Totals round sanely | P2 | Not run | Active |\n");
        com.gta.core.util.AtomicFiles.write(file, edited);

        CaseLedger l = CaseLedger.read(file, "order.create");
        l.syncWithSpec(spec(), "create.spec.md", Map.of());
        l.write(file);
        CaseLedger again = CaseLedger.read(file, "order.create");

        assertEquals(CaseLedger.RETIRED, again.row("order.create/s01").orElseThrow().status);
        assertEquals(1, again.manualRows().size());
        assertEquals("Pay in JPY | no decimals", again.manualRows().get(0).content, "pipes inside cells survive");
        assertEquals(CaseLedger.MANUAL, again.manualRows().get(0).automation);
    }

    @Test
    void shouldReadAMissingLedgerAsEmpty(@TempDir Path dir) {
        assertTrue(CaseLedger.read(dir.resolve("none.cases.md"), "order.none")
                .rows()
                .isEmpty());
    }

    @Test
    void shouldMarkOnlyActiveCasesForReviewAndRemoveRows() {
        CaseLedger l = synced();

        assertTrue(l.markForReview("order.create/s01"));
        assertFalse(l.markForReview("order.create/s01"), "already needs review");
        assertFalse(l.markForReview("order.create/s99"));
        l.remove("order.create/s02");

        assertEquals(
                List.of("order.create/s01"), l.rows().stream().map(r -> r.id).toList());
    }

    @Test
    void shouldCountCasesInTheIndex() {
        CaseLedger l = synced();
        l.recordResult("order.create/s01", "com.acme.OrderControllerIT#a", "Pass 2026-11-04 a1b2c3d", null);

        String index = CaseLedger.index(List.of(l));

        assertTrue(
                index.contains("| [order.create](order/create.cases.md) | 2 | 1 | 0 | 1 | 0 | 0 | 0 | 0/0 |"), index);
    }
}
