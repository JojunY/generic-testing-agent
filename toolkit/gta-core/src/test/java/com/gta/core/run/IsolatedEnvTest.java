package com.gta.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class IsolatedEnvTest {

    @Test
    void shouldCutOffCloudCredentialsAndCleanUpItsDirectory() throws Exception {
        Path dir;
        try (IsolatedEnv env = IsolatedEnv.create()) {
            dir = env.gcloudConfig();
            Files.writeString(dir.resolve("credentials.db"), "written by a test");
            assertEquals(dir.toString(), env.variables().get("CLOUDSDK_CONFIG"));
            assertTrue(env.variables().containsKey("GOOGLE_APPLICATION_CREDENTIALS"));
            assertNull(env.variables().get("GOOGLE_APPLICATION_CREDENTIALS"), "null removes the variable");
        }
        assertFalse(Files.exists(dir));
    }

    @Test
    void shouldNeverLetTheDevelopersSpringProfileReachAGtaRun() {
        try (IsolatedEnv env = IsolatedEnv.create()) {
            for (String name : java.util.List.of(
                    "SPRING_PROFILES_ACTIVE",
                    "SPRING_CONFIG_LOCATION",
                    "SPRING_APPLICATION_JSON",
                    "SPRING_CONFIG_IMPORT")) {
                assertTrue(env.variables().containsKey(name), name);
                assertNull(env.variables().get(name), name + " is removed, so a shell set to uat or prod stays out");
            }
        }

        assertEquals(
                "-Dhttps.proxyHost=127.0.0.1 -Xmx1g",
                IsolatedEnv.withoutSpringEnvironment(
                        "-Dhttps.proxyHost=127.0.0.1 -Dspring.profiles.active=prod -Xmx1g -Dspring.config.import=x"),
                "the proxy and memory options stay, the Spring selection goes");
        assertEquals("", IsolatedEnv.withoutSpringEnvironment("-Dspring.application.json={}"));
    }
}
