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
}
