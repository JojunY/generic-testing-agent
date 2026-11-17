package com.gta.core.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The oracle of a Cucumber scenario (plan §5.3, §7.10): Then steps are assertions, the rest is watched input. */
class FeatureScannerTest {
    static final String FEATURE = """
            # the borrows of one client
            @gta @component @flow:borrow.book
            Feature: Book a stock borrow

              Background:
                Given the lender "L-1" has 50,000 shares of "ACME"

              @scenario:borrow.book/s01 @hardening @p1
              Scenario: [borrow.book/s01] a borrow of no shares is refused
                When client "c-1" books a borrow of 0 shares of "ACME"
                Then the response status is 400

              Rule: inventory outages

                @scenario:borrow.book/s02 @hardening @p0
                Scenario: [borrow.book/s02] an unreachable inventory leaves the borrow pending
                  Given the inventory service is unreachable
                  When client "c-1" books a borrow of 10,000 shares of "ACME"
                  Then the response status is 503
                  And the borrow status is PENDING_LOCATE
                  But the borrow carries:
                    \"\"\"
                    {"reason": "inventory-unavailable"}
                    \"\"\"

                @scenario:borrow.book/s03 @characterization @p2
                Scenario Outline: [borrow.book/s03] fee per day
                  When a borrow of <quantity> shares accrues a day
                  Then the fee is <fee>
                  And the statement shows
                    | security | fee  |
                    | ACME     | 3.47 |

                  Examples:
                    | quantity | fee  |
                    | 10000    | 3.47 |
            """;

    @Test
    void shouldReadTagsStepsAndExamplesWithTheirInheritedTags() {
        FeatureScanner.Feature f = FeatureScanner.parse(FEATURE);

        assertEquals(Set.of("gta", "component", "flow:borrow.book"), f.tags());
        assertEquals(1, f.background().size());
        assertEquals(3, f.scenarios().size());
        FeatureScanner.Scenario s02 = f.scenarios().get(1);
        assertEquals("[borrow.book/s02] an unreachable inventory leaves the borrow pending", s02.name());
        assertEquals(Set.of("scenario:borrow.book/s02", "hardening", "p0"), s02.tags());
        assertTrue(s02.tagged("gta"), "feature tags are inherited");
        assertEquals(
                List.of("given", "when", "then", "then", "then"),
                s02.steps().stream().map(FeatureScanner.Step::section).toList(),
                "And and But continue the section before them");
        assertEquals(
                List.of("{\"reason\": \"inventory-unavailable\"}"),
                s02.steps().get(4).arguments());
        FeatureScanner.Scenario s03 = f.scenarios().get(2);
        assertEquals(List.of("security|fee", "ACME|3.47"), s03.steps().get(2).arguments(), "a step's data table");
        assertEquals(List.of("quantity=10000", "fee=3.47"), s03.examples());
    }

    @Test
    void shouldTakeTheThenValuesAsTheOracleAndWatchTheRest(@TempDir Path test) throws Exception {
        Path resources = test.resolve("resources");
        Files.createDirectories(resources.resolve("features/borrow"));
        Files.writeString(resources.resolve("features/borrow/book.feature"), FEATURE);

        AssertionScanner.ScenarioOracle s02 = FeatureScanner.scan(resources, "borrow.book/s02");

        assertEquals(1, s02.methods());
        assertEquals(
                3, s02.assertionStatements().size(), s02.assertionStatements().toString());
        assertTrue(
                s02.tokens().containsAll(List.of("503", "PENDING_LOCATE", "{\"reason\": \"inventory-unavailable\"}")));
        assertFalse(s02.tokens().contains("10,000"), "a When value is an input, not an expected value");
        AssertionScanner.ScenarioOracle s03 = FeatureScanner.scan(resources, "borrow.book/s03");
        assertTrue(s03.tokens().contains("3.47"));
        assertFalse(s03.tokens().contains("10000"), "the quantity column is an input: no Then step names it");
        assertFalse(FeatureScanner.scan(resources, "borrow.book/s09").found());

        // the steps of the scenario, the background and the step definitions are its inputs
        String before = s02.watchedHash();
        Files.createDirectories(test.resolve("java/steps"));
        Files.writeString(test.resolve("java/steps/BorrowSteps.java"), "import io.cucumber.java.en.Then;\nclass S {}");
        assertNotEquals(
                before, FeatureScanner.scan(resources, "borrow.book/s02").watchedHash());
        Files.writeString(test.resolve("java/steps/Other.java"), "class Other {}");
        String withGlue = FeatureScanner.scan(resources, "borrow.book/s02").watchedHash();
        Files.writeString(test.resolve("java/steps/Other.java"), "class Other { int x; }");
        assertEquals(withGlue, FeatureScanner.scan(resources, "borrow.book/s02").watchedHash(), "not glue");
    }

    @Test
    void shouldLockTheExamplesOfAnOutline(@TempDir Path test) throws Exception {
        Path resources = test.resolve("resources");
        Files.createDirectories(resources);
        Path feature = resources.resolve("book.feature");
        Files.writeString(
                feature, FEATURE.replace("| 3.47 |\n", "| 3.47 |\n                    | 20000    | 6.94 |\n"));
        List<String> tokens = List.of("6.94");
        String locked = OracleLock.fingerprint(OracleLock.lockedStatements(
                FeatureScanner.scan(resources, "borrow.book/s03").assertionStatements(), tokens));

        // the Then step reads "the fee is <fee>": the expected value lives in the examples
        Files.writeString(
                feature, FEATURE.replace("| 3.47 |\n", "| 3.47 |\n                    | 20000    | 9.99 |\n"));
        String changed = OracleLock.fingerprint(OracleLock.lockedStatements(
                FeatureScanner.scan(resources, "borrow.book/s03").assertionStatements(), tokens));

        assertNotEquals(locked, changed);
    }

    @Test
    void shouldFindAScenarioNoTestMethodCarriesInTheFeatures(@TempDir Path test) throws Exception {
        Path resources = test.resolve("resources");
        Files.createDirectories(resources.resolve("features"));
        Files.writeString(resources.resolve("features/book.feature"), FEATURE);
        Path suite = test.resolve("java/RunCucumberTest.java");
        Files.createDirectories(suite.getParent());
        Files.writeString(suite, "@Suite @IncludeEngines(\"cucumber\") public class RunCucumberTest {}");

        AssertionScanner.ScenarioOracle oracle = AssertionScanner.scan(suite, "borrow.book/s01", resources);

        assertEquals(1, oracle.methods());
        assertTrue(oracle.tokens().contains("400"));
        assertEquals(0, AssertionScanner.scan(suite, null, resources).methods(), "a seed test is never a feature");
    }

    @Test
    void shouldRemoveOneScenarioAndKeepTheOthers() {
        String rest = FeatureScanner.withoutScenario(FEATURE, "borrow.book/s02").orElseThrow();

        FeatureScanner.Feature f = FeatureScanner.parse(rest);
        assertEquals(
                List.of("[borrow.book/s01] a borrow of no shares is refused", "[borrow.book/s03] fee per day"),
                f.scenarios().stream().map(FeatureScanner.Scenario::name).toList());
        assertFalse(rest.contains("inventory-unavailable"), "the scenario's doc string goes with it");
        assertTrue(rest.contains("Rule: inventory outages"));
        assertTrue(FeatureScanner.withoutScenario(FEATURE, "borrow.book/s09").isEmpty());
    }

    @Test
    void shouldKnowTheBlockKeywords() {
        assertEquals("scenario", FeatureScanner.keyword("Scenario Outline: x"));
        assertEquals("examples", FeatureScanner.keyword("Scenarios:"));
        assertEquals(null, FeatureScanner.keyword("Given a value: 3"));
    }
}
