package com.gta.core.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Spec refs resolve to content hashes, so an approved spec notices when the code it describes changes (plan §3.3). */
class RefResolverTest {
    @TempDir
    Path root;

    @BeforeEach
    void service() throws Exception {
        Path src = root.resolve("src/main/java/com/acme/Pricing.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package com.acme;\nclass Pricing { int discount() { return 10; } }\n");
        Path res = root.resolve("src/main/resources");
        Files.createDirectories(res);
        Files.writeString(res.resolve("application.properties"), "order.discount.rate=10\n");
        Files.writeString(res.resolve("application.yml"), """
                order:
                  discount:
                    threshold: 100
                    rate: 99
                  regions: [eu, us]
                ---
                spring.profiles: test
                """);
        Files.writeString(res.resolve("logback.xml"), "<configuration/>");
    }

    @Test
    void shouldHashMethodsAndConfigValues() throws Exception {
        RefResolver refs = RefResolver.of(root);

        Optional<String> method = refs.hash("method:com.acme.Pricing#discount");
        assertTrue(method.isPresent());
        assertTrue(refs.hash("config:order.discount.threshold").isPresent(), "yaml keys are flattened");
        assertEquals(Optional.empty(), refs.hash("method:com.acme.Pricing#missing"));
        assertEquals(Optional.empty(), refs.hash("config:order.unknown"));
        assertEquals(Optional.empty(), refs.hash("doc:README.md"));

        Files.writeString(
                root.resolve("src/main/java/com/acme/Pricing.java"),
                "package com.acme;\nclass Pricing { int discount() { return 11; } }\n");
        assertNotEquals(
                method,
                RefResolver.of(root).hash("method:com.acme.Pricing#discount"),
                "a code change changes the hash");
    }

    @Test
    void shouldPreferPropertiesOverYamlAndFlattenLists() {
        Map<String, String> config = RefResolver.loadConfig(root.resolve("src/main/resources"));

        assertEquals("10", config.get("order.discount.rate"), "the first file read wins");
        assertEquals("eu", config.get("order.regions[0]"));
        assertEquals("test", config.get("spring.profiles"), "every yaml document is read");
        assertTrue(RefResolver.loadConfig(root.resolve("none")).isEmpty());
    }

    @org.junit.jupiter.api.Test
    void shouldHashAFileOfTheModuleButNothingOutsideIt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path module)
            throws Exception {
        java.nio.file.Path sql = module.resolve("src/main/resources/db/migration/V2__tier.sql");
        java.nio.file.Files.createDirectories(sql.getParent());
        java.nio.file.Files.writeString(sql, "update customer set tier = 'GOLD';");
        RefResolver refs = RefResolver.of(module);

        String before =
                refs.hash("file:src/main/resources/db/migration/V2__tier.sql").orElseThrow();
        java.nio.file.Files.writeString(sql, "update customer set tier = 'SILVER';");
        org.junit.jupiter.api.Assertions.assertNotEquals(
                before,
                RefResolver.of(module)
                        .hash("file:src/main/resources/db/migration/V2__tier.sql")
                        .orElseThrow(),
                "a changed migration makes its spec stale");
        org.junit.jupiter.api.Assertions.assertTrue(
                refs.hash("file:../outside.sql").isEmpty(), "never outside the module");
        org.junit.jupiter.api.Assertions.assertTrue(
                refs.hash("file:missing.sql").isEmpty());
    }
}
