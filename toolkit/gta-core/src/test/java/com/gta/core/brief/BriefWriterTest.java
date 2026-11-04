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
}
