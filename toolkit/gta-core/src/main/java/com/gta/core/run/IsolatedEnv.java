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
 * Environment for one test run with cloud credentials cut off, so tests can never reach real GCP (plan §13), and with
 * the developer's Spring profile and configuration variables removed: a shell set to SIT, UAT or production
 * ({@code SPRING_PROFILES_ACTIVE=uat}) never makes a gta run load that environment's configuration. A null value removes
 * the variable. {@link #close()} deletes the temporary empty gcloud config directory.
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
        for (String spring : SPRING_ENVIRONMENT) {
            variables.put(spring, null);
        }
    }

    /** Environment variables that pick a Spring profile or configuration from outside the repository. */
    static final java.util.List<String> SPRING_ENVIRONMENT = java.util.List.of(
            "SPRING_PROFILES_ACTIVE",
            "SPRING_PROFILES_INCLUDE",
            "SPRING_PROFILES_DEFAULT",
            "SPRING_APPLICATION_JSON",
            "SPRING_CONFIG_NAME",
            "SPRING_CONFIG_LOCATION",
            "SPRING_CONFIG_ADDITIONAL_LOCATION",
            "SPRING_CONFIG_IMPORT");

    private static final java.util.regex.Pattern SPRING_SYSTEM_PROPERTY =
            java.util.regex.Pattern.compile("-Dspring\\.(profiles|config|application\\.json)[^\\s]*");

    /**
     * Inherited JAVA_TOOL_OPTIONS without the system properties that would select a Spring profile or configuration
     * (proxy, trust store and other options stay).
     */
    public static String withoutSpringEnvironment(String javaToolOptions) {
        return SPRING_SYSTEM_PROPERTY
                .matcher(javaToolOptions)
                .replaceAll("")
                .replaceAll("\\s+", " ")
                .strip();
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
