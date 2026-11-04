package com.gta.core.run;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
