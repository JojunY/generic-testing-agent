package com.gta.core.change;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** gta tests run locally only (plan §13): owned test files and fixtures never name SIT, UAT or production hosts. */
class ProtectedHostsTest {
    @TempDir
    Path root;

    private void write(String rel, String text) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    @Test
    void shouldFindAProtectedHostInTheItemsOwnFilesOnly() throws Exception {
        write("src/test/java/com/acme/OrderControllerIT.java", "String base = \"https://orders-UAT.acme.com/api\";");
        write("src/test/resources/com/acme/OrderControllerIT/order.json", "{\"id\": 1}");
        write("src/test/java/com/acme/DeveloperIT.java", "String base = \"https://orders-uat.acme.com\";");

        List<String> hits = ProtectedHosts.named(
                root,
                root.resolve("src/test"),
                List.of(
                        "src/test/java/com/acme/OrderControllerIT.java",
                        "src/test/resources/com/acme/OrderControllerIT/**"),
                List.of("orders-uat.acme.com", "db-prod.acme.com"));

        assertEquals(
                List.of("src/test/java/com/acme/OrderControllerIT.java: orders-uat.acme.com"),
                hits,
                "matched case-insensitively; the developer's own test is not gta's to judge");
    }

    @Test
    void shouldFindNothingWithoutProtectedHosts() throws Exception {
        write(
                "src/test/resources/application-test.properties",
                "spring.datasource.url=jdbc:oracle:thin:@db-prod.acme.com");

        assertTrue(ProtectedHosts.named(root, root.resolve("src/test"), List.of("src/test/resources/**"), List.of())
                .isEmpty());
        assertEquals(
                1,
                ProtectedHosts.named(
                                root,
                                root.resolve("src/test"),
                                List.of("src/test/resources/**"),
                                List.of("db-prod.acme.com"))
                        .size(),
                "the foundation item owns the test resources, so a test configuration on prod is caught too");
    }
}
