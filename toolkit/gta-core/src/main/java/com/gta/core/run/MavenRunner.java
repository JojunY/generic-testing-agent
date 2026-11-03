package com.gta.core.run;

import com.gta.core.config.GtaConfig;
import com.gta.core.config.GtaPaths;
import com.gta.core.util.Json;
import com.gta.core.util.Proc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs tests through Maven Surefire with -Dtest/-Dgroups. All test execution goes through here, so gtaw - not the
 * agent - decides what "passed" means. Cloud credentials are isolated for every run (plan §13).
 */
public final class MavenRunner {
    private static final Pattern COMPILE_ERROR = Pattern.compile("\\[ERROR\\]\\s+(/?\\S+\\.java):\\[(\\d+),(\\d+)]\\s*(.*)");

    public record Options(String testClass, String scenarioTag, boolean stabilityVariant, boolean randomOrder) {
    }

    private final GtaPaths paths;
    private final GtaConfig config;

    public MavenRunner(GtaPaths paths, GtaConfig config) {
        this.paths = paths;
        this.config = config;
    }

    public List<String> mavenCommand() {
        Path root = paths.root();
        String configured = config.build.maven;
        if (configured != null && !"auto".equals(configured)) {
            return new ArrayList<>(List.of(configured));
        }
        if (Proc.isWindows()) {
            if (Files.exists(root.resolve("mvnw.cmd"))) {
                return new ArrayList<>(List.of("cmd", "/c", "mvnw.cmd"));
            }
            return new ArrayList<>(List.of("cmd", "/c", "mvn"));
        }
        if (Files.exists(root.resolve("mvnw"))) {
            return new ArrayList<>(List.of("sh", "mvnw"));
        }
        return new ArrayList<>(List.of("mvn"));
    }

    public RunResult run(String runId, String itemId, Options options, Set<String> productionClasses, Set<String> testClasses) {
        Path root = paths.root();
        Path surefire = root.resolve("target/surefire-reports");
        Path gtaResults = root.resolve("target/gta-results");
        deleteDir(surefire);
        deleteDir(gtaResults);

        List<String> cmd = mavenCommand();
        cmd.addAll(List.of("-B", "-Dsurefire.failIfNoSpecifiedTests=false", "-DfailIfNoTests=false",
                "-Djacoco.skip=true", "-Dgta.results.dir=" + gtaResults));
        if (options.testClass() != null) {
            cmd.add("-Dtest=" + options.testClass());
        }
        if (options.scenarioTag() != null) {
            cmd.add("-Dgroups=" + options.scenarioTag());
        }
        cmd.add("test");

        Map<String, String> env = isolatedEnv();
        List<String> toolOptions = new ArrayList<>();
        String inherited = System.getenv("JAVA_TOOL_OPTIONS");
        if (inherited != null && !inherited.isBlank()) {
            toolOptions.add(inherited);
        }
        if (options.stabilityVariant()) {
            toolOptions.add("-Duser.timezone=Asia/Kolkata -Duser.language=de -Duser.country=DE");
        }
        if (options.randomOrder()) {
            toolOptions.add("-Djunit.jupiter.testmethod.order.default=org.junit.jupiter.api.MethodOrderer$Random");
        }
        if (!toolOptions.isEmpty()) {
            env.put("JAVA_TOOL_OPTIONS", String.join(" ", toolOptions));
        }

        long start = System.currentTimeMillis();
        Proc.Result proc = Proc.run(root, Duration.ofMinutes(config.budgets.nodeTimeoutMinutes), env, cmd);
        RunResult result = new RunResult();
        result.runId = runId;
        result.itemId = itemId;
        result.command = cmd;
        result.exitCode = proc.exitCode();
        result.durationMs = System.currentTimeMillis() - start;
        String output = proc.output();
        if (proc.exitCode() != 0 && (output.contains("COMPILATION ERROR") || output.contains("Compilation failure"))) {
            result.compileError = true;
            result.compileMessage = compileSummary(output);
        }
        result.cases = SurefireReports.parse(List.of(surefire), gtaResults);
        for (TestCase c : result.cases) {
            FailureClassifier.classify(c, productionClasses, testClasses);
        }
        Path runDir = paths.runs().resolve(runId);
        try {
            Files.createDirectories(runDir);
            Files.writeString(runDir.resolve("maven.log"), output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        result.logFile = paths.rel(runDir.resolve("maven.log"));
        Json.writeAtomic(runDir.resolve("run.json"), result);
        return result;
    }

    /** Environment with cloud credentials cut off so non-system tests can never reach real GCP (plan §13). */
    public static Map<String, String> isolatedEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        try {
            env.put("CLOUDSDK_CONFIG", Files.createTempDirectory("gta-gcloud-empty").toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        env.put("GOOGLE_APPLICATION_CREDENTIALS", null);
        env.put("KUBECONFIG", null);
        env.put("NO_GCE_CHECK", "true");
        env.put("GCE_METADATA_HOST", "localhost:1");
        env.put("GCE_METADATA_IP", "127.0.0.1:1");
        return env;
    }

    static String compileSummary(String output) {
        StringBuilder sb = new StringBuilder();
        Matcher m = COMPILE_ERROR.matcher(output);
        int n = 0;
        while (m.find() && n++ < 10) {
            sb.append(m.group(1).replaceAll(".*/src/", "src/")).append(':').append(m.group(2)).append(' ').append(m.group(4)).append('\n');
        }
        return sb.length() == 0 ? "compilation failed (see maven.log)" : sb.toString().trim();
    }

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // stale reports are harmless; they are filtered by test class later
        }
    }
}
