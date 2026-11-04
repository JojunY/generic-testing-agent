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

        boolean mvnw = Files.exists(root.resolve(ProcessRunner.isWindows() ? "mvnw.cmd" : "mvnw"));
        boolean mvn;
        try {
            mvn = mvnw
                    || ProcessRunner.run(
                                    root,
                                    ProcessRunner.isWindows()
                                            ? ProcessRunner.cmd("cmd", "/c", "mvn", "-v")
                                            : ProcessRunner.cmd("mvn", "-v"))
                            .ok();
        } catch (GtaException e) {
            mvn = false;
        }
        add(
                "maven",
                mvn ? "ok" : "fail",
                mvnw ? "Maven wrapper" : mvn ? "mvn on PATH" : "no Maven",
                "Install Maven or add the Maven wrapper");

        List<String> dependencies = List.of();
        try {
            PomInfo pom = PomInfo.read(root);
            dependencies = pom.dependencies;
            add(
                    "spring-boot",
                    pom.bootMajor() >= 3 ? "ok" : "warn",
                    "Spring Boot " + pom.bootVersion + " → JUnit " + pom.junitMajor(),
                    "gta targets Spring Boot 3.5 and 4.x");
            boolean junit = pom.dependencies.contains("gta-junit");
            add(
                    "gta-junit",
                    junit ? "ok" : "warn",
                    junit ? "test dependency present" : "missing",
                    "Add com.gta:gta-junit (scope test) from Nexus; the first foundation item can do it");
            boolean failsafe = pom.plugins.contains("maven-failsafe-plugin");
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
}
