package com.gta.core.run;

import com.gta.core.config.GtaConfig;
import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.util.AtomicFiles;
import com.gta.core.util.Json;
import com.gta.core.util.ProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs tests through Maven Surefire with -Dtest/-Dgroups. All test execution goes through here, so gtaw - not the
 * agent - decides what "passed" means. Cloud credentials are isolated for every run (plan §13).
 */
public class MavenRunner implements TestRunner {
    private static final Pattern COMPILE_ERROR =
            Pattern.compile("\\[ERROR\\]\\s+(/?\\S+\\.java):\\[(\\d+),(\\d+)]\\s*(.*)");

    protected final GtaPaths paths;
    protected final GtaConfig config;

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
        if (ProcessRunner.isWindows()) {
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

    /**
     * Every gta test class by its suffix (docs/guides/test-naming.md): Test and SliceTest, IT and ContractIT. Karate and
     * E2E tests run only through their own profiles, so they are excluded explicitly instead of relying on defaults.
     */
    static final String ALL_TESTS = "**/*Test,**/*IT,!**/*E2ETest";

    @Override
    public RunResult run(
            String runId, String itemId, Options options, Set<String> productionClasses, Set<String> testClasses) {
        Path root = paths.root();
        Path reports = reportDir();
        Path gtaResults = paths.gtaResults();
        deleteDir(reports);
        deleteDir(gtaResults);

        List<String> cmd = testCommand(options, gtaResults);

        IsolatedEnv isolated = IsolatedEnv.create();
        Map<String, String> env = isolated.variables();
        List<String> toolOptions = new ArrayList<>();
        String inherited = System.getenv("JAVA_TOOL_OPTIONS");
        if (inherited != null
                && !IsolatedEnv.withoutSpringEnvironment(inherited).isEmpty()) {
            toolOptions.add(IsolatedEnv.withoutSpringEnvironment(inherited));
        }
        if (options.stabilityVariant()) {
            toolOptions.add(stabilityVariantOptions(config.qualityGates));
        }
        if (options.randomOrder()) {
            toolOptions.add("-Djunit.jupiter.testmethod.order.default=org.junit.jupiter.api.MethodOrderer$Random");
        }
        if (!toolOptions.isEmpty()) {
            env.put("JAVA_TOOL_OPTIONS", String.join(" ", toolOptions));
        }
        String javaHome = javaHome(config.build.javaHomeEnv, System::getenv);
        if (javaHome != null) {
            env.put("JAVA_HOME", javaHome);
        }

        long start = System.currentTimeMillis();
        ProcessRunner.Result proc;
        try {
            proc = ProcessRunner.run(root, Duration.ofMinutes(config.budgets.nodeTimeoutMinutes), env, cmd);
        } finally {
            isolated.close(); // the empty gcloud config dir exists for this run only
        }
        // a hanging test is a result, not an error: the caller counts it against the item's budgets
        boolean timedOut = proc.timedOut();
        RunResult result = new RunResult();
        result.runId = runId;
        result.itemId = itemId;
        result.command = cmd;
        result.exitCode = proc.exitCode();
        result.timedOut = timedOut;
        result.durationMs = System.currentTimeMillis() - start;
        String output = proc.output();
        if (proc.exitCode() != 0 && compileFailed(output)) {
            result.compileError = true;
            result.compileMessage = compileErrors(output);
        }
        result.cases = SurefireReportParser.parse(List.of(reports), gtaResults, timedOut);
        for (TestOutcome c : result.cases) {
            FailureClassifier.classify(c, productionClasses, testClasses);
        }
        Path runDir = paths.runs().resolve(runId);
        AtomicFiles.write(runDir.resolve(logName()), output);
        result.logFile = paths.rel(runDir.resolve(logName()));
        Json.writeAtomic(runDir.resolve("run.json"), result);
        return result;
    }

    /** The build command that runs the selected tests and writes JUnit XML reports to {@link #reportDir()}. */
    protected List<String> testCommand(Options options, Path gtaResults) {
        List<String> cmd = mavenCommand();
        cmd.addAll(List.of(
                "-B",
                "-Dsurefire.failIfNoSpecifiedTests=false",
                "-DfailIfNoTests=false",
                "-Djacoco.skip=true",
                "-Dgta.results.dir=" + gtaResults));
        cmd.addAll(moduleArgs(paths.module()));
        if (options.testClass() != null) {
            cmd.add("-Dtest=" + options.testClass());
        } else {
            // Surefire's default includes skip *IT; gtaw runs component tests through Surefire too (no Failsafe needed)
            cmd.add("-Dtest=" + ALL_TESTS);
        }
        if (options.scenarioTag() != null) {
            cmd.add("-Dgroups=" + options.scenarioTag());
        }
        cmd.add("test");
        return cmd;
    }

    /** Where the build writes the JUnit XML reports of a run. */
    protected Path reportDir() {
        return paths.surefireReports();
    }

    protected boolean compileFailed(String output) {
        return output.contains("COMPILATION ERROR") || output.contains("Compilation failure");
    }

    protected String compileErrors(String output) {
        return compileSummary(output);
    }

    /** File name of the build output kept in the run directory. */
    protected String logName() {
        return "maven.log";
    }

    /** JVM options of the stability variant: another timezone and locale (plan §8). */
    static String stabilityVariantOptions(GtaConfig.QualityGates gates) {
        Locale locale = Locale.forLanguageTag(gates.stabilityLocale);
        return "-Duser.timezone=" + gates.stabilityTimezone + " -Duser.language=" + locale.getLanguage()
                + (locale.getCountry().isEmpty() ? "" : " -Duser.country=" + locale.getCountry());
    }

    /** How the stability variant is described in messages, e.g. "Asia/Kolkata timezone / de-DE locale". */
    public static String stabilityVariantLabel(GtaConfig.QualityGates gates) {
        return gates.stabilityTimezone + " timezone / " + gates.stabilityLocale + " locale";
    }

    static String compileSummary(String output) {
        StringBuilder sb = new StringBuilder();
        Matcher m = COMPILE_ERROR.matcher(output);
        int n = 0;
        while (m.find() && n++ < 10) {
            sb.append(m.group(1).replaceAll(".*/src/", "src/"))
                    .append(':')
                    .append(m.group(2))
                    .append(' ')
                    .append(m.group(4))
                    .append('\n');
        }
        return sb.length() == 0
                ? "compilation failed (see maven.log)"
                : sb.toString().trim();
    }

    static void deleteDir(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            // reports left over from an earlier run would be read as this run's results
            throw new GtaException(
                    ErrorCode.ENV_TEST_REPORTS_UNUSABLE,
                    "Cannot clear " + dir + ": " + e.getMessage(),
                    "Close programs that hold files in it (IDE, antivirus scan), then run the command again");
        }
    }

    /**
     * The JDK Maven runs with: the directory in {@code build.javaHomeEnv}'s variable, or null to keep the inherited
     * JAVA_HOME. A configured but unset variable fails instead of silently building with the wrong JDK.
     */
    static String javaHome(String envName, java.util.function.Function<String, String> getenv) {
        if (envName == null || envName.isBlank()) {
            return null;
        }
        String value = getenv.apply(envName);
        if (value == null || value.isBlank()) {
            throw new GtaException(
                    ErrorCode.ENV_TOOL_UNAVAILABLE,
                    "build.javaHomeEnv names " + envName + ", which is not set",
                    "Set " + envName + " to the JDK this service builds with (for example a JDK 8), or remove"
                            + " build.javaHomeEnv from .gta/config.json");
        }
        return value;
    }

    /**
     * In a multi-module repository Maven runs from the root on the service module and the modules it depends on
     * ({@code -pl <module> -am}), so siblings are built from the working tree instead of a stale local repository.
     */
    static List<String> moduleArgs(String module) {
        return module == null || module.isEmpty() ? List.of() : List.of("-pl", module, "-am");
    }
}
