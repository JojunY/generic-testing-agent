package com.gta.core.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.profile.RefResolver;
import com.gta.core.registry.CapabilityRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecLinterTest {
    @TempDir
    Path root;

    private List<String> lint(String text) throws Exception {
        Path src = root.resolve("src/main/java/com/acme/order");
        Files.createDirectories(src);
        Files.writeString(
                src.resolve("OrderController.java"),
                """
                package com.acme.order;
                public class OrderController { public Object create() { return null; } }
                """);
        return SpecLinter.lint(
                SpecParser.parseText(text),
                "order.create",
                RefResolver.of(root),
                CapabilityRegistry.loadBundled(),
                root);
    }

    @Test
    void shouldAcceptAValidSpec() throws Exception {
        List<String> issues = lint(SpecTestData.CREATE_ORDER);

        assertTrue(issues.isEmpty(), issues.toString());
    }

    @Test
    void shouldListScenariosWithoutAPriorityWithoutCallingThemInvalid() throws Exception {
        assertTrue(lint(SpecTestData.CREATE_ORDER).isEmpty(), "a missing priority is not a lint error");

        List<String> missing = SpecLinter.missingPriority(SpecParser.parseText(SpecTestData.CREATE_ORDER));
        List<String> withPriority = SpecLinter.missingPriority(SpecParser.parseText(
                SpecTestData.CREATE_ORDER.replace("hint.layer=component", "priority=P0 hint.layer=component")));

        assertTrue(missing.contains("order.create/s01"), missing.toString());
        assertTrue(missing.size() > withPriority.size(), "a scenario with priority= is no longer listed");
    }

    @Test
    void shouldRejectAnAgentWrittenConfirmation() throws Exception {
        String forged = SpecTestData.CREATE_ORDER.replace(
                "source=observed hint.layer=component hint.oracle=response",
                "source=confirmed hint.layer=component hint.oracle=response");

        List<String> issues = lint(forged);

        assertTrue(issues.stream().anyMatch(i -> i.contains("confirmed")), issues.toString());
    }

    @Test
    void shouldRequireExpectedValuesInBackticks() throws Exception {
        String vague = SpecTestData.CREATE_ORDER.replace(
                "- **THEN** the response status is `201`\n- **AND** the total is `50.00`",
                "- **THEN** the order is created correctly");

        List<String> issues = lint(vague);

        assertTrue(issues.stream().anyMatch(i -> i.toLowerCase().contains("then")), issues.toString());
    }

    @Test
    void shouldRequireAQuestionForAnAssumedScenario() throws Exception {
        String unasked = SpecTestData.CREATE_ORDER.replace("affects=order.create/s02", "affects=order.create/s01");

        List<String> issues = lint(unasked);

        assertTrue(issues.stream().anyMatch(i -> i.contains("s02")), issues.toString());
    }

    @Test
    void shouldAcceptCommentsBeforeTheDiagramTypeAndTheTwoStyleClasses() {
        String diagram =
                """
                %% order.create: the client creates an order
                flowchart LR
                  C[Client] --> API[OrderController]:::focus
                  API --> PG[payment-gateway]:::external
                  classDef focus stroke:#bf4520,stroke-width:1.5px
                  classDef external stroke-dasharray:4 4
                """;

        assertTrue(
                SpecLinter.lintDiagram(diagram).isEmpty(),
                SpecLinter.lintDiagram(diagram).toString());
    }

    @Test
    void shouldRejectThemeOverridesAndOtherStyling() {
        String diagram =
                """
                %%{init: {'theme':'dark'}}%%
                flowchart TD
                  A --> B
                  classDef loud fill:#ff0000
                  style A fill:#00ff00
                """;

        java.util.List<String> issues = SpecLinter.lintDiagram(diagram);

        assertEquals(3, issues.size(), issues.toString());
        assertTrue(issues.get(0).contains("theme override"), issues.toString());
    }

    @Test
    void shouldRejectAnUnsupportedDiagramType() {
        assertTrue(SpecLinter.lintDiagram("pie title Pets\n  \"Dogs\" : 386\n")
                .get(0)
                .contains("unsupported mermaid type"));
    }

    private static final String BROKEN =
            """
            # Order.Create — broken on purpose
            <!-- gta: flowId=Order.Create capability=teleport risk=5 status=sketch -->

            ## Requirements

            ### Requirement: No EARS
            <!-- gta: id=order.create/x01 ref=method:com.acme.order.Missing#gone -->
            IF something happens the service reacts.

            ### Requirement: Empty
            <!-- gta: id=order.create/r02 -->
            THE service SHALL exist.

            ### Requirement: Bad scenarios
            <!-- gta: id=order.create/r03 -->
            THE service SHALL validate.

            #### Scenario: invented source
            <!-- gta: id=order.create/s01 source=rumour hint.layer=moon hint.oracle=vibes priority=P9 -->
            - **WHEN** a client posts
            - **THEN** something happens

            #### Scenario: self-confirmed
            <!-- gta: source=confirmed by=R9 -->
            - **WHEN** a client posts

            #### Scenario: documented without a quote
            <!-- gta: id=order.create/s03 source=documented doc=README.md -->
            - **WHEN** a client posts
            - **THEN** status `201`

            #### Scenario: quote not in the doc
            <!-- gta: id=order.create/s04 source=documented doc=README.md#api quote="returns 418" -->
            - **WHEN** a client posts
            - **THEN** status `201`

            #### Scenario: unanswered assumption
            <!-- gta: id=order.create/s05 source=assumed -->
            - **WHEN** a client posts
            - **THEN** status `201`

            ## Invariants
            - totals are never negative <!-- gta: id=order.create/i01 source=assumed -->

            ## Open questions
            <!-- gta: id=order.create/q01 impact=medium -->
            1. [medium] Retry?
               - [ ] A) Yes

            ## Findings
            """;

    @Test
    void shouldReportEveryBrokenRule() throws Exception {
        Files.writeString(root.resolve("README.md"), "# API\nIt returns 201.\n");

        String issues = String.join(
                "\n",
                SpecLinter.lint(
                        SpecParser.parseText(BROKEN),
                        "order.create",
                        RefResolver.of(root),
                        CapabilityRegistry.loadBundled(),
                        root));

        for (String expected : List.of(
                "flowId 'Order.Create' must be <domain>.<flow-name>",
                "does not match the file location",
                "unknown capability 'teleport'",
                "status must be one of",
                "risk must be probability",
                "id 'order.create/x01' must look like Order.Create/r01",
                "use EARS wording with SHALL",
                "an 'IF' requirement needs 'THEN'",
                "needs at least one '#### Scenario:'",
                "does not resolve",
                "source must be one of",
                "hint.layer must be one of",
                "hint.oracle must be one of",
                "priority must be P0",
                "THEN must contain at least one expected value",
                "missing <!-- gta: id=",
                "can only be written by gtaw from an answer record",
                "needs at least one '- **THEN** ...' clause",
                "needs doc=<path>#<anchor> and quote=",
                "quote not found verbatim in README.md#api",
                "an assumed scenario must be covered by an open question",
                "an assumed invariant must be covered by an open question",
                "impact must be high or normal",
                "needs at least two options",
                "state the effect on THEN",
                "needs affects=")) {
            assertTrue(issues.contains(expected), "missing issue '" + expected + "' in:\n" + issues);
        }
    }

    @Test
    void shouldStopAtAMissingHeader() {
        List<String> issues = SpecLinter.lint(
                SpecParser.parseText("# no header\n"), null, null, CapabilityRegistry.loadBundled(), null);

        assertEquals(List.of("header: missing <!-- gta: flowId=... --> comment under the title"), issues);
    }

    @Test
    void shouldRequireAnAccessSectionAndACurrentCodeOptionForNormalQuestions() {
        String spec = SpecTestData.CREATE_ORDER
                .replaceAll("(?s)## Access.*?## Requirements", "## Requirements")
                .replace("impact=high", "impact=normal");

        String issues = String.join(
                "\n", SpecLinter.lint(SpecParser.parseText(spec), null, null, CapabilityRegistry.loadBundled(), null));

        assertTrue(issues.contains("need a '## Access' section"), issues);
    }

    @Test
    void shouldReportAnUnreadableDocument() {
        String spec = SpecTestData.CREATE_ORDER.replace(
                "source=observed hint.layer=component hint.oracle=response",
                "source=documented doc=missing.md#x quote=\"returns 201\" hint.layer=component");

        String issues = String.join(
                "\n", SpecLinter.lint(SpecParser.parseText(spec), null, null, CapabilityRegistry.loadBundled(), root));

        assertTrue(issues.contains("doc missing.md#x is not readable"), issues);
    }
}
