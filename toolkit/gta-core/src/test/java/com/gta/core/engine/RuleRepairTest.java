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
        i.id = "price.update-price/s01#component-01";
        i.kind = "test";
        i.ref = "price.update-price/s01";
        i.flowId = "price.update-price";
        return i;
    }

    private static final String TEST = """
            package com.acme;

            import org.junit.jupiter.api.DisplayName;
            import org.junit.jupiter.api.Test;

            class PriceIT {
                @Test
                @Tag("gta")
                @DisplayName("[price.update-price/s01] an update returns the new price")
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
                fixed.contains("    @Tag(\"flow:price.update-price\")\n    @Tag(\"scenario:price.update-price/s01\")\n"
                        + "    @DisplayName(\"[price.update-price/s01]"),
                fixed);
        assertEquals(3, done.size(), done.toString());
        assertTrue(done.stream().noneMatch(d -> d.contains("NoSuchHelper")), "only well-known types are imported");
    }

    @Test
    void shouldLeaveAWellFormedTestAndForeignFilesAlone() throws Exception {
        Path file = dir.resolve("PriceIT.java");
        String tagged = TEST.replace("@Tag(\"gta\")", "@Tag(\"gta\")\n    @Tag(\"scenario:price.update-price/s01\")");
        Files.writeString(file, tagged);

        assertEquals(List.of(), RuleRepair.apply(file, item(), "", true));
        assertEquals(tagged, Files.readString(file));
        assertEquals(List.of(), RuleRepair.apply(dir.resolve("Missing.java"), item(), "", true));

        PlanItem foundation = item();
        foundation.kind = "foundation";
        assertEquals(
                List.of(), RuleRepair.apply(file, foundation, "symbol: class Tag", true), "the seed is left alone");
    }
}
