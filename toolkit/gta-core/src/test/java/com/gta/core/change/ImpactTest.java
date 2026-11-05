package com.gta.core.change;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.config.GtaPaths;
import com.gta.core.profile.JavaIndex;
import com.gta.core.util.Git;
import com.gta.core.util.ProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Impact beyond the changed files (plan §15, P1): the bean graph and changed config values. */
class ImpactTest {
    @TempDir
    Path root;

    private void source(String fqcn, String body) throws Exception {
        Path f = root.resolve("src/main/java").resolve(fqcn.replace('.', '/') + ".java");
        Files.createDirectories(f.getParent());
        String pkg = fqcn.substring(0, fqcn.lastIndexOf('.'));
        Files.writeString(f, "package " + pkg + ";\n" + body + "\n");
    }

    @Test
    void shouldReachEveryClassThatInjectsAChangedClassTransitively() throws Exception {
        source("com.acme.api.OrderController", "class OrderController { private final OrderService service; }");
        source("com.acme.service.OrderService", "class OrderService { OrderService(PricingService pricing) {} }");
        source("com.acme.service.PricingService", "class PricingService {}");
        source("com.acme.service.Unrelated", "class Unrelated { String name; }");
        source("com.acme.repo.JpaRepo", "class JpaRepo implements OrderRepository {}");
        source("com.acme.repo.OrderRepository", "interface OrderRepository {}");

        Map<String, String> affected = Impact.affectedClasses(
                JavaIndex.of(root.resolve("src/main/java")), Set.of("com.acme.service.PricingService"));

        assertEquals("com.acme.service.PricingService", affected.get("com.acme.api.OrderController"), "two hops away");
        assertTrue(affected.containsKey("com.acme.service.OrderService"), "constructor injection");
        assertFalse(affected.containsKey("com.acme.service.Unrelated"));

        Map<String, String> byInterface = Impact.affectedClasses(
                JavaIndex.of(root.resolve("src/main/java")), Set.of("com.acme.repo.OrderRepository"));
        assertTrue(byInterface.containsKey("com.acme.repo.JpaRepo"), "an implementation follows its interface");
    }

    @Test
    void shouldNameTheChangedMainClassesOfTheServiceModule() {
        GtaPaths paths = new GtaPaths(root, "order-api");

        assertEquals(
                Set.of("com.acme.Order"),
                Impact.changedClasses(
                        paths,
                        List.of(
                                "order-api/src/main/java/com/acme/Order.java",
                                "order-api/src/test/java/com/acme/OrderIT.java",
                                "other/src/main/java/com/acme/Other.java")));
    }

    private void git(String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessRunner.run(root, Duration.ofSeconds(30), Map.of(), cmd);
    }

    @Test
    void shouldListTheConfigKeysWhoseValueChanged() throws Exception {
        Path yml = root.resolve("src/main/resources/application.yml");
        Files.createDirectories(yml.getParent());
        Files.writeString(yml, "order:\n  max-items: 10\n  currency: EUR\n");
        Files.writeString(root.resolve("src/main/resources/application.properties"), "payment.timeout=5s\n");
        git("init", "-q");
        git("config", "user.email", "t@example.com");
        git("config", "user.name", "t");
        git("add", "-A");
        git("commit", "-qm", "base");
        Git g = new Git(root);
        String base = g.head();
        Files.writeString(yml, "order:\n  max-items: 20\n  currency: EUR\n  new-key: x\n");

        Set<String> keys = Impact.changedConfigKeys(
                g, new GtaPaths(root), base, List.of("src/main/resources/application.yml", "README.md"));

        assertEquals(Set.of("order.max-items", "order.new-key"), keys, "an unchanged value is not affected");
    }
}
