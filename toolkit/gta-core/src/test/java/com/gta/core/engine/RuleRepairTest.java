package com.gta.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.plan.PlanItem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rules before the LLM (plan §2): deterministic repairs of the item's own test. */
class RuleRepairTest {
    @TempDir
    Path dir;

    private static PlanItem item() {
        PlanItem i = new PlanItem();
        i.id = "price.update/s01#component-01";
        i.kind = "test";
        i.ref = "price.update/s01";
        i.flowId = "price.update";
        return i;
    }

    private static final String TEST = """
            package com.acme;

            import org.junit.jupiter.api.DisplayName;
            import org.junit.jupiter.api.Test;

            class PriceIT {
                @Test
                @Tag("gta")
                @DisplayName("[price.update/s01] an update returns the new price")
                void shouldReturnTheNewPrice() {
                    BigDecimal price = new BigDecimal("7.25");
                }
            }
            """;

    @Test
    void shouldTagAMethodNamedAfterTheScenarioAndAddTheImportsTheBuildMissed() throws Exception {
        Path file = dir.resolve("PriceIT.java");
        Files.writeString(file, TEST);
        String log = "[ERROR] PriceIT.java:[12,9] cannot find symbol\n  symbol:   class BigDecimal\n"
                + "[ERROR] PriceIT.java:[8,6] cannot find symbol\n  symbol:   class Tag\n  symbol:   class NoSuchHelper\n";

        List<String> done = RuleRepair.apply(file, item(), log, true);

        String fixed = Files.readString(file);
        assertTrue(
                fixed.contains("import java.math.BigDecimal;") && fixed.contains("import org.junit.jupiter.api.Tag;"),
                fixed);
        assertTrue(
                fixed.contains("    @Tag(\"flow:price.update\")\n    @Tag(\"scenario:price.update/s01\")\n"
                        + "    @DisplayName(\"[price.update/s01]"),
                fixed);
        assertEquals(3, done.size(), done.toString());
        assertTrue(done.stream().noneMatch(d -> d.contains("NoSuchHelper")), "only well-known types are imported");
    }

    @Test
    void shouldLeaveAWellFormedTestAndForeignFilesAlone() throws Exception {
        Path file = dir.resolve("PriceIT.java");
        String tagged = TEST.replace("@Tag(\"gta\")", "@Tag(\"gta\")\n    @Tag(\"scenario:price.update/s01\")");
        Files.writeString(file, tagged);

        assertEquals(List.of(), RuleRepair.apply(file, item(), "", true));
        assertEquals(tagged, Files.readString(file));
        assertEquals(List.of(), RuleRepair.apply(dir.resolve("Missing.java"), item(), "", true));

        PlanItem foundation = item();
        foundation.kind = "foundation";
        assertEquals(
                List.of(), RuleRepair.apply(file, foundation, "symbol: class Tag", true), "the seed is left alone");
    }

    @Test
    void shouldGiveTheScenarioMethodExactlyThePlansIntentAndPriorityTags() throws Exception {
        PlanItem item = item();
        item.intent = "hardening";
        item.priority = "p0";
        Path file = dir.resolve("PriceIT.java");
        String scenario = "@Tag(\"gta\")\n    @Tag(\"scenario:price.update/s01\")\n"
                + "    @Tag(\"characterization\")\n    @Tag(\"p1\")\n    @Tag(\"p2\")";
        Files.writeString(file, TEST.replace("@Tag(\"gta\")", scenario));

        List<String> done = RuleRepair.normalizeTags(file, item);

        String text = Files.readString(file);
        assertEquals(1, done.size(), done.toString());
        assertTrue(
                text.contains("@Tag(\"scenario:price.update/s01\")\n    @Tag(\"hardening\")\n    @Tag(\"p0\")\n"
                        + "    @DisplayName"),
                text);
        assertTrue(!text.contains("characterization") && !text.contains("\"p1\"") && !text.contains("\"p2\""), text);
        assertTrue(text.contains("BigDecimal price = new BigDecimal(\"7.25\")"), "the body is untouched");
        assertEquals(List.of(), RuleRepair.normalizeTags(file, item), "already right: nothing to do");
    }

    @Test
    void shouldKeepTheTagsOutOfTheMethodBodyWhenTheDeclarationSharesTheLine() {
        PlanItem item = item();
        item.intent = "hardening";
        item.priority = "p0";
        String oneLine = TEST.replace(
                "@Tag(\"gta\")\n    @DisplayName(\"[price.update/s01] an update returns the new price\")\n    void shouldReturnTheNewPrice() {",
                "@Test @Tag(\"scenario:price.update/s01\") void shouldReturnTheNewPrice() {");
        assertTrue(oneLine.contains("void shouldReturnTheNewPrice() {"), oneLine);

        String text = RuleRepair.normalizeTags(oneLine, item, new java.util.ArrayList<>());

        assertTrue(
                text.contains(
                        "    @Tag(\"hardening\")\n    @Tag(\"p0\")\n    @Test @Tag(\"scenario:price.update/s01\") void"),
                text);
    }

    @Test
    void shouldFindTheScenarioTagAmongOthersOnOneLine() {
        PlanItem item = item();
        item.intent = "characterization";
        item.priority = "p1";
        String inline = TEST.replace(
                "@Tag(\"gta\")", "@Tag(\"flow:price.update\") @Tag(\"scenario:price.update/s01\") @Tag(\"p2\")");
        List<String> done = new java.util.ArrayList<>();

        String text = RuleRepair.normalizeTags(inline, item, done);

        assertTrue(
                text.contains("    @Tag(\"flow:price.update\") @Tag(\"scenario:price.update/s01\")\n"
                        + "    @Tag(\"characterization\")\n    @Tag(\"p1\")\n    @DisplayName"),
                text);
        assertTrue(done.get(0).contains("was @Tag(\"p2\")"), done.toString());
    }

    @Test
    void shouldLeaveTagsAloneWithoutTheScenarioTagOrForTheSeed() throws Exception {
        PlanItem item = item();
        item.intent = "hardening";
        item.priority = "p0";
        Path file = dir.resolve("PriceIT.java");
        Files.writeString(file, TEST);

        assertEquals(
                List.of(), RuleRepair.normalizeTags(file, item), "no scenario tag: the run reports TEST_NOT_FOUND");
        item.kind = "foundation";
        assertEquals(List.of(), RuleRepair.normalizeTags(file, item));
        assertEquals(TEST, Files.readString(file));
    }

    @Test
    void shouldGiveACucumberScenarioThePlansTagsAndKeepItsPin() throws Exception {
        PlanItem item = item();
        item.layer = "component";
        item.intent = "hardening";
        item.priority = "p0";
        Path feature = dir.resolve("update.feature");
        Files.writeString(feature, """
                @gta @component
                Feature: price updates

                  @scenario:price.update/s01 @characterization @p2 @known-defect @known-defect:abc123
                  Scenario: [price.update/s01] an update returns the new price
                    When the price of "ACME" is set to 7.25
                    Then the price is 7.25

                  Scenario: [price.update/s02] untouched
                    Then nothing changes
                """);

        List<String> done = RuleRepair.normalizeFeatureTags(feature, item);

        String text = Files.readString(feature);
        assertEquals(1, done.size(), done.toString());
        assertTrue(
                text.contains(
                        "  @flow:price.update @scenario:price.update/s01 @hardening @p0 @known-defect @known-defect:abc123\n"
                                + "  Scenario: [price.update/s01]"),
                text);
        assertTrue(text.contains("    Then the price is 7.25"), "steps are never touched");
        assertTrue(RuleRepair.normalizeFeatureTags(feature, item).isEmpty(), "right tags stay as they are");
    }

    @Test
    void shouldTagACucumberScenarioNamedAfterTheScenario() {
        PlanItem item = item();
        item.layer = "component";
        item.intent = "characterization";
        item.priority = "p1";
        List<String> done = new java.util.ArrayList<>();

        String text = RuleRepair.normalizeFeatureTags(
                "Feature: prices\n\n  Scenario: [price.update/s01] an update\n    Then the price is 7.25\n",
                item,
                done);

        assertTrue(
                text.contains("  @gta @component @flow:price.update @scenario:price.update/s01 @characterization @p1\n"
                        + "  Scenario: [price.update/s01] an update"),
                text);
        assertTrue(done.get(0).startsWith("added @scenario:price.update/s01"), done.toString());
        assertEquals(
                "Feature: other\n",
                RuleRepair.normalizeFeatureTags("Feature: other\n", item, new java.util.ArrayList<>()),
                "a feature without the scenario is left alone");
    }
}
