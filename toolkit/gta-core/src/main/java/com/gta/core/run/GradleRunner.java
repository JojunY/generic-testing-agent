package com.gta.core.run;

import com.gta.core.config.GtaConfig;
import com.gta.core.config.GtaPaths;
import com.gta.core.profile.PomInfo;
import com.gta.core.util.AtomicFiles;
import com.gta.core.util.ProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs tests through Gradle (plan §15 P3). gtaw never edits the service's build script: an init script it writes to
 * .gta/work selects the scenario's tag, passes gta-junit its results directory and keeps E2E tests out. The Test task's
 * JUnit XML has Surefire's format, so results, isolation and classification are shared with Maven runs.
 */
public final class GradleRunner extends MavenRunner {
    static final String INIT_SCRIPT = """
            // written by gtaw (plan §15 P3): selects gta's tests without changing the service's build script
            allprojects {
                tasks.withType(Test).configureEach { t ->
                    def groups = project.findProperty('gta.groups')
                    t.useJUnitPlatform { if (groups) includeTags groups.toString() }
                    def results = project.findProperty('gta.results.dir')
                    if (results) t.systemProperty 'gta.results.dir', results.toString()
                    t.filter { excludeTestsMatching '*E2ETest'; failOnNoMatchingTests = false }
                    t.outputs.upToDateWhen { false }
                }
            }
            """;

    private static final Pattern COMPILE_ERROR = Pattern.compile("(?m)^(\\S+\\.java):(\\d+): error: (.*)$");

    public GradleRunner(GtaPaths paths, GtaConfig config) {
        super(paths, config);
    }

    /**
     * Whether the service builds with Gradle: build.tool=gradle, or build.tool=auto and the module (or repository root)
     * has a Gradle build file but no pom.xml.
     */
    public static boolean selected(GtaPaths paths, GtaConfig config) {
        String tool = config.build.tool;
        if ("gradle".equals(tool)) {
            return true;
        }
        if ("maven".equals(tool)) {
            return false;
        }
        Path module = paths.moduleRoot();
        return !Files.exists(module.resolve("pom.xml"))
                && (PomInfo.gradleBuildFile(module).isPresent()
                        || PomInfo.gradleBuildFile(paths.root()).isPresent());
    }

    public List<String> gradleCommand() {
        String configured = config.build.gradle;
        if (configured != null && !"auto".equals(configured)) {
            return new ArrayList<>(List.of(configured));
        }
        Path root = paths.root();
        if (ProcessRunner.isWindows()) {
            return new ArrayList<>(
                    Files.exists(root.resolve("gradlew.bat"))
                            ? List.of("cmd", "/c", "gradlew.bat")
                            : List.of("cmd", "/c", "gradle"));
        }
        return new ArrayList<>(Files.exists(root.resolve("gradlew")) ? List.of("sh", "gradlew") : List.of("gradle"));
    }

    @Override
    protected List<String> testCommand(Options options, Path gtaResults) {
        Path init = paths.work().resolve("gradle/gta-init.gradle");
        AtomicFiles.write(init, INIT_SCRIPT);
        List<String> cmd = gradleCommand();
        cmd.addAll(
                List.of("--console=plain", "-I", init.toAbsolutePath().toString(), "-Pgta.results.dir=" + gtaResults));
        if (options.scenarioTag() != null) {
            cmd.add("-Pgta.groups=" + options.scenarioTag());
        }
        cmd.add(testTask(paths.module()));
        if (options.testClass() != null) {
            cmd.addAll(List.of("--tests", options.testClass()));
        }
        return cmd;
    }

    /** {@code test}, or {@code :order-api:test} for a subproject. */
    public static String testTask(String module) {
        return module == null || module.isEmpty() ? "test" : ":" + module.replace('/', ':') + ":test";
    }

    @Override
    protected Path reportDir() {
        return paths.moduleRoot().resolve("build/test-results/test");
    }

    @Override
    protected boolean compileFailed(String output) {
        return output.contains("Compilation failed")
                || COMPILE_ERROR.matcher(output).find();
    }

    @Override
    protected String compileErrors(String output) {
        StringBuilder sb = new StringBuilder();
        Matcher m = COMPILE_ERROR.matcher(output);
        int n = 0;
        while (m.find() && n++ < 10) {
            sb.append(m.group(1).replaceAll(".*/src/", "src/"))
                    .append(':')
                    .append(m.group(2))
                    .append(' ')
                    .append(m.group(3))
                    .append('\n');
        }
        return sb.length() == 0
                ? "compilation failed (see build.log)"
                : sb.toString().trim();
    }

    @Override
    protected String logName() {
        return "build.log";
    }
}
