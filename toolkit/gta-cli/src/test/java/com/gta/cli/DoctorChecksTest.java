package com.gta.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DoctorChecksTest {
    @Test
    void shouldRunDependencyChecksOnlyForServicesThatUseTheDependency() {
        List<String> deps = List.of("spring-boot-starter-web", "ojdbc11", "spring-cloud-gcp-starter-pubsub");

        assertTrue(DoctorChecks.uses(deps, "ojdbc"));
        assertTrue(DoctorChecks.uses(deps, "pubsub"));
        assertFalse(DoctorChecks.uses(List.of("spring-boot-starter-web"), "ojdbc"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> check(Path root, String name) {
        List<Map<String, String>> checks =
                (List<Map<String, String>>) new DoctorChecks(root).run().get("checks");
        return checks.stream()
                .filter(c -> name.equals(c.get("check")))
                .findFirst()
                .orElseThrow();
    }

    private static Path pom(Path root, String modules) throws Exception {
        Files.writeString(
                root.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>a</groupId><artifactId>b</artifactId>"
                        + "<version>1</version>" + modules + "</project>");
        return root;
    }

    @Test
    void shouldWarnThatMultiModuleServicesAreNotSupportedYet(@TempDir Path root) throws Exception {
        Map<String, String> multi = check(pom(root, "<modules><module>api</module></modules>"), "modules");

        assertEquals("warn", multi.get("status"));
        assertTrue(multi.get("detail").contains("api"), multi.toString());
        assertTrue(multi.get("hint").contains("single-module"), multi.toString());
    }

    @Test
    void shouldAcceptASingleModuleService(@TempDir Path root) throws Exception {
        assertEquals("ok", check(pom(root, ""), "modules").get("status"));
    }
}
