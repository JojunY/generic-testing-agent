package com.gta.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DoctorChecksTest {
    @Test
    void shouldRunDependencyChecksOnlyForServicesThatUseTheDependency() {
        List<String> deps = List.of("spring-boot-starter-web", "ojdbc11", "spring-cloud-gcp-starter-pubsub");

        assertTrue(DoctorChecks.uses(deps, "ojdbc"));
        assertTrue(DoctorChecks.uses(deps, "pubsub"));
        assertFalse(DoctorChecks.uses(List.of("spring-boot-starter-web"), "ojdbc"));
    }
}
