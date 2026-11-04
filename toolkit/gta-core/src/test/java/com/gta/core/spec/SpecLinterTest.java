package com.gta.core.spec;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.profile.RefResolver;
import com.gta.core.registry.Registry;
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
                SpecParser.parseText(text), "order.create", RefResolver.of(root), Registry.loadBundled(), root);
    }

    @Test
    void shouldAcceptAValidSpec() throws Exception {
        List<String> issues = lint(SpecFixtures.CREATE_ORDER);

        assertTrue(issues.isEmpty(), issues.toString());
    }

    @Test
    void shouldRejectAnAgentWrittenConfirmation() throws Exception {
        String forged = SpecFixtures.CREATE_ORDER.replace(
                "source=observed hint.layer=component hint.oracle=response",
                "source=confirmed hint.layer=component hint.oracle=response");

        List<String> issues = lint(forged);

        assertTrue(issues.stream().anyMatch(i -> i.contains("confirmed")), issues.toString());
    }

    @Test
    void shouldRequireExpectedValuesInBackticks() throws Exception {
        String vague = SpecFixtures.CREATE_ORDER.replace(
                "- **THEN** the response status is `201`\n- **AND** the total is `50.00`",
                "- **THEN** the order is created correctly");

        List<String> issues = lint(vague);

        assertTrue(issues.stream().anyMatch(i -> i.toLowerCase().contains("then")), issues.toString());
    }

    @Test
    void shouldRequireAQuestionForAnAssumedScenario() throws Exception {
        String unasked = SpecFixtures.CREATE_ORDER.replace("affects=order.create/s02", "affects=order.create/s01");

        List<String> issues = lint(unasked);

        assertTrue(issues.stream().anyMatch(i -> i.contains("s02")), issues.toString());
    }
}
