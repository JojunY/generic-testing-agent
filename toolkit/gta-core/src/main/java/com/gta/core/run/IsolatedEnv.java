package com.gta.core.run;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Environment for one test run with cloud credentials cut off, so non-system tests can never reach real GCP (plan §13).
 * A null value removes the variable. {@link #close()} deletes the temporary empty gcloud config directory.
 */
public final class IsolatedEnv implements AutoCloseable {
    private final Path gcloudConfig;
    private final Map<String, String> variables = new LinkedHashMap<>();

    private IsolatedEnv(Path gcloudConfig) {
        this.gcloudConfig = gcloudConfig;
        variables.put("CLOUDSDK_CONFIG", gcloudConfig.toString());
        variables.put("GOOGLE_APPLICATION_CREDENTIALS", null);
        variables.put("KUBECONFIG", null);
        variables.put("NO_GCE_CHECK", "true");
        variables.put("GCE_METADATA_HOST", "localhost:1");
        variables.put("GCE_METADATA_IP", "127.0.0.1:1");
    }

    public static IsolatedEnv create() {
        try {
            return new IsolatedEnv(Files.createTempDirectory("gta-gcloud-empty"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Mutable: callers add run options such as JAVA_TOOL_OPTIONS. */
    public Map<String, String> variables() {
        return variables;
    }

    public Path gcloudConfig() {
        return gcloudConfig;
    }

    @Override
    public void close() {
        if (!Files.exists(gcloudConfig)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(gcloudConfig)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
