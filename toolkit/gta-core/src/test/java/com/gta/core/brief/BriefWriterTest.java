package com.gta.core.brief;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.config.GtaPaths;
import com.gta.core.plan.PlanItem;
import com.gta.core.profile.ServiceProfile;
import com.gta.core.spec.Spec;
import com.gta.core.spec.SpecParser;
import com.gta.core.spec.SpecTestData;
import com.gta.core.state.WorkState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Briefs are self-contained instructions for one node (plan §4.2): everything the agent may and must do is in them. */
class BriefWriterTest {
    @TempDir
    Path root;

    private PlanItem item() {
        PlanItem i = new PlanItem();
        i.id = "order.create/s01#component-01";
        i.kind = "test";
        i.ref = "order.create/s01";
        i.flowId = "order.create";
        i.layer = "component";
        i.intent = "characterization";
        i.priority = "p1";
        i.testClass = "com.acme.order.OrderControllerIT";
        i.ownsFiles.add("src/test/java/com/acme/order/OrderControllerIT.java");
        i.oracle.then = List.of("201", "50.00");
        i.skills = List.of(".gta/skills/gta-writing-tests/SKILL.md");
        return i;
    }

    private WorkState.ItemState state() {
        WorkState.ItemState s = new WorkState.ItemState();
        s.id = item().id;
        s.token = BriefWriter.token("s1", 7);
        return s;
    }

    private Spec spec() {
        return SpecParser.parseText(SpecTestData.CREATE_ORDER);
    }

    @Test
    void shouldNumberBriefsAndBindThemToAToken() {
        assertEquals("s1:0007", BriefWriter.token("s1", 7));
    }

    @Test
    void shouldGiveTheAuthorTheClassItsFilesAndTheExpectedValues() throws Exception {
        Path brief = new BriefWriter(new GtaPaths(root)).author(7, state(), item(), spec(), new ServiceProfile());

        String text = Files.readString(brief);
        assertEquals(root.resolve(".gta/work/briefs/0007.md"), brief);
        for (String expected : List.of(
                "author `order.create/s01#component-01`",
                "Token: `s1:0007`",
                "Test class: `com.acme.order.OrderControllerIT`",
                "src/test/java/com/acme/order/OrderControllerIT.java",
                "- `201`",
                "- `50.00`",
                ".gta/skills/gta-writing-tests/SKILL.md")) {
            assertTrue(text.contains(expected), "missing '" + expected + "' in:\n" + text);
        }
    }

    @Test
    void shouldTellTheAuthorHowToPinAConfirmedDefect() throws Exception {
        WorkState.ItemState s = state();
        s.intentOverride = "known-defect";
        s.lastError = new WorkState.LastError();
        s.lastError.defectFingerprint = "abc123def456";

        String text = Files.readString(
                new BriefWriter(new GtaPaths(root)).author(8, s, item(), spec(), new ServiceProfile()));

        assertTrue(text.contains("@KnownDefect(id = \"order.create/s01\", failure = \"abc123def456\")"), text);
    }

    @Test
    void shouldGiveTheReviewerTheVerdictsAndTheAnalystTheLintIssues() throws Exception {
        BriefWriter writer = new BriefWriter(new GtaPaths(root));

        String review = Files.readString(writer.review(9, state(), item(), spec(), "triage", "The test failed with X"));
        String analyze = Files.readString(writer.analyze(
                10,
                "s1:0010",
                "order.create",
                null,
                "rest-api",
                List.of("gta-writing-behavior-specs"),
                List.of("requirement 'x': use EARS"),
                List.of("q01: B"),
                true));

        assertTrue(review.contains("gta-reviewer") && review.contains("The test failed with X"), review);
        assertTrue(analyze.contains("use EARS") && analyze.contains("q01: B"), analyze);
        assertTrue(analyze.contains("--token s1:0010"), analyze);
    }

    @org.junit.jupiter.api.Test
    void shouldAskForTestsAtTheServiceJavaLevel() {
        org.junit.jupiter.api.Assertions.assertTrue(BriefWriter.javaLine(21).startsWith("Java 21 "));
        org.junit.jupiter.api.Assertions.assertTrue(
                BriefWriter.javaLine(17).contains("newer than Java 17"), "a Java 17 service gets no Java 21 syntax");
        org.junit.jupiter.api.Assertions.assertTrue(
                BriefWriter.javaLine(0).startsWith("Java 17 "), "an unknown level falls back to gta's floor");
        org.junit.jupiter.api.Assertions.assertTrue(
                BriefWriter.javaLine(8).startsWith("Java 8 "), "Java 8 services too");
        org.junit.jupiter.api.Assertions.assertTrue(BriefWriter.namespaceLine(2).contains("javax"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "", BriefWriter.namespaceLine(3), "Boot 3+ uses jakarta by default");
    }

    @org.junit.jupiter.api.Test
    void shouldShowTheLessonsOfEarlierRuns(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) {
        com.gta.core.config.GtaPaths paths = new com.gta.core.config.GtaPaths(root);
        StringBuilder none = new StringBuilder();
        new BriefWriter(paths).appendSkills(none, java.util.List.of("gta-writing-tests"));
        org.junit.jupiter.api.Assertions.assertFalse(none.toString().contains("Lessons"), "no section without lessons");

        com.gta.core.state.Lessons.record(paths, "reviewer", "rest-api component test", "assert the stored state too");
        StringBuilder b = new StringBuilder();
        new BriefWriter(paths).appendSkills(b, java.util.List.of("gta-writing-tests"));

        org.junit.jupiter.api.Assertions.assertTrue(
                b.toString().contains("## Lessons from earlier runs")
                        && b.toString().contains("- [reviewer] rest-api component test: assert the stored state too"),
                b.toString());
    }

    @org.junit.jupiter.api.Test
    void shouldListTheRepositorysOwnSpecsAsDocumentedSources(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root)
            throws Exception {
        java.nio.file.Path open = root.resolve("openspec/specs/orders/spec.md");
        java.nio.file.Files.createDirectories(open.getParent());
        java.nio.file.Files.writeString(open, "### Requirement: Create an order");
        java.nio.file.Path kit = root.resolve("specs/001-orders/spec.md");
        java.nio.file.Files.createDirectories(kit.getParent());
        java.nio.file.Files.writeString(kit, "## User Scenarios");

        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of("openspec/specs/orders/spec.md"), BriefWriter.existingSpecs(root, "openspec"));
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of("specs/001-orders/spec.md"), BriefWriter.existingSpecs(root, "speckit"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(), BriefWriter.existingSpecs(root, "gta"));

        StringBuilder b = new StringBuilder();
        new BriefWriter(new com.gta.core.config.GtaPaths(root), "openspec").appendExistingSpecs(b);
        org.junit.jupiter.api.Assertions.assertTrue(
                b.toString().contains("source=documented") && b.toString().contains("Never edit"), b.toString());
    }

    @Test
    void shouldTellTheAuthorThatAReproducingTestMustFail() throws Exception {
        PlanItem incident = item();
        incident.intent = "reproducing";

        String text = Files.readString(
                new BriefWriter(new GtaPaths(root)).author(9, state(), incident, spec(), new ServiceProfile()));

        assertTrue(text.contains("must fail on the current code"), text);
        assertTrue(text.contains("do not add `@KnownDefect`"), "a human confirms the defect before any pin: " + text);
    }

    @Test
    void shouldLetTheAssessorReportACatchWhateverTheOracleSource() throws Exception {
        PlanItem catching = item();
        catching.intent = "catching";
        catching.oracle.source = "observed";

        String text = Files.readString(new BriefWriter(new GtaPaths(root))
                .review(10, state(), catching, spec(), "triage", "The test failed."));

        assertTrue(text.contains("`product-defect` — a catch"), text);
    }
}
