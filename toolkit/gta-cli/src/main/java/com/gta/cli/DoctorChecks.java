package com.gta.cli;

import com.gta.core.config.GtaConfig;
import com.gta.core.config.GtaPaths;
import com.gta.core.error.GtaException;
import com.gta.core.profile.PomInfo;
import com.gta.core.util.Git;
import com.gta.core.util.Hashes;
import com.gta.core.util.Json;
import com.gta.core.util.ProcessRunner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Environment and installation checks with actionable hints (plan §11.2). */
public final class DoctorChecks {
    private final Path root;
    private final List<Map<String, String>> checks = new ArrayList<>();

    public DoctorChecks(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Map<String, Object> run() {
        int javaMajor = Runtime.version().feature();
        add("java", javaMajor >= 17 ? "ok" : "fail", "Java " + javaMajor, "Install JDK 17 or newer");

        boolean git = new Git(root).isRepo();
        add(
                "git",
                git ? "ok" : "fail",
                git ? "git work tree" : "not a git work tree",
                "Run gtaw inside the service repository");

        GtaPaths repositoryPaths = new GtaPaths(root);
        boolean gradle;
        try {
            GtaConfig config = GtaConfig.load(repositoryPaths);
            gradle = com.gta.core.run.GradleRunner.selected(repositoryPaths.withModule(config.build.module), config);
        } catch (GtaException e) {
            gradle = false; // the config check below reports it
        }
        if (gradle) {
            buildTool(
                    "gradle",
                    ProcessRunner.isWindows() ? "gradlew.bat" : "gradlew",
                    "gradle",
                    "Gradle",
                    "Install Gradle or add the Gradle wrapper");
        } else {
            buildTool(
                    "maven",
                    ProcessRunner.isWindows() ? "mvnw.cmd" : "mvnw",
                    "mvn",
                    "Maven",
                    "Install Maven or add the Maven wrapper");
        }

        List<String> dependencies = List.of();
        String module = configuredModule();
        try {
            PomInfo repositoryPom = PomInfo.read(root);
            PomInfo pom = module.isEmpty() ? repositoryPom : PomInfo.read(root, module);
            dependencies = pom.dependencies;
            add(
                    "spring-boot",
                    pom.bootMajor() >= 3 ? "ok" : "warn",
                    "Spring Boot " + pom.bootVersion + " → JUnit " + pom.junitMajor(),
                    pom.bootMajor() == 2
                            ? "Spring Boot 2 is out of open-source support; gta writes JUnit 5 tests with javax.* for it"
                                    + " (Boot 2.0 and 2.1 also need org.junit.jupiter:junit-jupiter as a test dependency)"
                            : "gta targets Spring Boot 2.2 to 4.x");
            int serviceJava = pom.javaMajor();
            add(
                    "service-java",
                    serviceJava == 0 || serviceJava >= 17 ? "ok" : serviceJava >= 8 ? "warn" : "fail",
                    serviceJava == 0
                            ? "Java level not set in the build file (tests are written for Java 17)"
                            : "Java " + serviceJava
                                    + (serviceJava < 17 ? ": tests are written for Java " + serviceJava : ""),
                    serviceJava >= 8
                            ? "If this build needs an older JDK than the one gtaw runs on, set build.javaHomeEnv in"
                                    + " .gta/config.json to the name of a variable holding that JDK"
                            : "gta supports services on Java 8 or newer");
            boolean multiModule = !repositoryPom.modules.isEmpty();
            add(
                    "modules",
                    !multiModule || repositoryPom.modules.contains(module) ? "ok" : "fail",
                    !multiModule
                            ? "single module"
                            : module.isEmpty()
                                    ? "multi-module " + repositoryPom.modules + ", build.module not set"
                                    : "module " + module + " of " + repositoryPom.modules,
                    "Set build.module in .gta/config.json to the module that holds the service (one of "
                            + repositoryPom.modules + ")");
            boolean junit = pom.dependencies.contains("gta-junit");
            add(
                    "gta-junit",
                    junit ? "ok" : "warn",
                    junit ? "test dependency present" : "missing",
                    "Add com.gta:gta-junit (scope test) from Nexus; the first foundation item can do it");
            boolean failsafe = pom.plugins.contains("maven-failsafe-plugin");
            if (!gradle)
                add(
                        "failsafe",
                        failsafe ? "ok" : "warn",
                        failsafe ? "configured" : "not configured",
                        "gtaw runs *IT classes through Surefire -Dtest; add Failsafe so mvn verify runs them in CI");
        } catch (GtaException e) {
            add("pom", "fail", e.getMessage(), e.hint());
        }

        GtaPaths paths = new GtaPaths(root);
        try {
            GtaConfig config = GtaConfig.load(paths);
            add(
                    "config",
                    "ok",
                    Files.exists(paths.config()) ? ".gta/config.json valid" : "defaults (no .gta/config.json)",
                    "");
            boolean hosts = !config.safety.protectedHosts.isEmpty();
            add(
                    "protected-hosts",
                    hosts ? "ok" : "warn",
                    hosts ? config.safety.protectedHosts.size() + " protected hosts" : "safety.protectedHosts not set",
                    "List your SIT, UAT and production hosts in safety.protectedHosts (.gta/config.json): gtaw then"
                            + " refuses any test or fixture that names one, so gta tests can only run locally");
            if (config.build.javaHomeEnv != null) {
                String jdk = System.getenv(config.build.javaHomeEnv);
                boolean present = jdk != null && Files.isDirectory(Path.of(jdk));
                add(
                        "build-jdk",
                        present ? "ok" : "fail",
                        present
                                ? config.build.javaHomeEnv + " = " + jdk
                                : config.build.javaHomeEnv + " is not a JDK directory",
                        "Set " + config.build.javaHomeEnv + " to the JDK this service builds with");
            }
            // dependency-specific checks only run for services that use the dependency
            if (uses(dependencies, "ojdbc")) {
                boolean oracle = config.environments.oracle.testUrl != null;
                add(
                        "oracle-test-db",
                        oracle ? "ok" : "warn",
                        oracle ? "shared Oracle test schema configured" : "not configured",
                        "Set environments.oracle.testUrl for high-fidelity Oracle tests; otherwise H2 MODE=Oracle (low fidelity) is used");
            }
            if (uses(dependencies, "pubsub")) {
                boolean tools = config.distribution.toolsUrl != null;
                add(
                        "emulators",
                        tools ? "ok" : "warn",
                        tools ? "tools from " + config.distribution.toolsUrl : "no tools URL",
                        "Upload the Pub/Sub emulator to the Nexus raw repository and set distribution.toolsUrl; otherwise fakes are used");
            }
        } catch (GtaException e) {
            add("config", "fail", e.getMessage(), e.hint());
        }

        Properties wrapper = new Properties();
        if (Files.exists(paths.wrapperProperties())) {
            try (var in = Files.newInputStream(paths.wrapperProperties())) {
                wrapper.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        String url = wrapper.getProperty("distributionUrl", "");
        boolean pinned = !wrapper.getProperty("distributionSha256Sum", "").isBlank()
                && !url.isBlank()
                && !url.contains("nexus.example.com");
        add(
                "wrapper",
                pinned ? "ok" : "fail",
                pinned ? "gta-cli pinned to " + url : "distributionUrl/distributionSha256Sum not set",
                "Set both in " + paths.rel(paths.wrapperProperties())
                        + " (gtaw refuses to run an unpinned jar; GTA_CLI_JAR overrides for development)");

        Path managed = paths.managedFiles();
        if (Files.exists(managed)) {
            Map<?, ?> m = Json.read(managed, Map.class);
            List<String> modified = new ArrayList<>();
            Object files = m.get("files");
            if (files instanceof Map<?, ?> fm) {
                fm.forEach((k, v) -> {
                    Path f = root.resolve(String.valueOf(k));
                    if (!Files.exists(f) || !Hashes.sha256(f).equals(v)) {
                        modified.add(String.valueOf(k));
                    }
                });
            }
            add(
                    "harness",
                    modified.isEmpty() ? "ok" : "warn",
                    m.get("platform") + " " + m.get("version")
                            + (modified.isEmpty() ? ", all managed files intact" : ", modified: " + modified),
                    "Run ./gtaw upgrade to restore managed files (local edits are reported, not overwritten)");
            if ("copilot".equals(m.get("platform"))) {
                add(
                        "ide-auto-approve",
                        "info",
                        "VS Code: .vscode/settings.json auto-approves ./gtaw next|check|submit|answer|status",
                        "IntelliJ: Settings > GitHub Copilot > Chat > Auto Approve → add rules for ./gtaw and .\\gtaw.cmd (same subcommands)");
            }
        } else {
            add("harness", "fail", "not installed", "Run ./gtaw init --platform copilot (or opencode)");
        }
        if (ProcessRunner.isWindows()) {
            add(
                    "windows",
                    "info",
                    "Windows detected",
                    "Use .\\gtaw.cmd; keep the repository on a short path to avoid long-path issues");
        }

        boolean fail = checks.stream().anyMatch(c -> c.get("status").equals("fail"));
        boolean warn = checks.stream().anyMatch(c -> c.get("status").equals("warn"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("overall", fail ? "fail" : warn ? "warn" : "ok");
        out.put("checks", checks);
        return out;
    }

    private void add(String name, String status, String detail, String hint) {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("check", name);
        c.put("status", status);
        c.put("detail", detail);
        if (!"ok".equals(status) && hint != null && !hint.isEmpty()) {
            c.put("hint", hint);
        }
        checks.add(c);
    }

    /** Whether any dependency's artifactId contains {@code fragment} (e.g. ojdbc11, spring-cloud-gcp-starter-pubsub). */
    static boolean uses(List<String> dependencies, String fragment) {
        return dependencies.stream().anyMatch(a -> a != null && a.contains(fragment));
    }

    /** build.module from .gta/config.json; "" when unset or when the config cannot be read (the config check reports that). */
    /** The build tool's check: its wrapper in the repository, or the executable on PATH. */
    private void buildTool(String name, String wrapper, String executable, String label, String hint) {
        boolean hasWrapper = Files.exists(root.resolve(wrapper));
        boolean found;
        try {
            found = hasWrapper
                    || ProcessRunner.run(
                                    root,
                                    ProcessRunner.isWindows()
                                            ? ProcessRunner.cmd("cmd", "/c", executable, "-v")
                                            : ProcessRunner.cmd(executable, "-v"))
                            .ok();
        } catch (GtaException e) {
            found = false;
        }
        add(
                name,
                found ? "ok" : "fail",
                hasWrapper ? label + " wrapper" : found ? executable + " on PATH" : "no " + label,
                hint);
    }

    private String configuredModule() {
        try {
            String m = GtaConfig.load(new GtaPaths(root)).build.module;
            return m == null ? "" : m;
        } catch (GtaException e) {
            return "";
        }
    }
}
