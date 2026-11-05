package com.gta.core.run;

import com.gta.core.config.GtaConfig;
import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.util.Git;
import com.gta.core.util.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * {@code gtaw mutate --changed} (plan §3.4, §15 P2): PIT on the production classes changed since a base, run with the
 * service's unit and slice tests only, and counted on the changed lines. Advisory: it reports, the gate does not fail
 * on it until it is calibrated on pilot repositories.
 */
public final class MutationRunner {
    /** PIT runs from its command-line entry point, so the service's pom needs no plugin. */
    static final List<String> PIT_ARTIFACTS = List.of(
            "org.pitest:pitest:1.30.1",
            "org.pitest:pitest-entry:1.30.1",
            "org.pitest:pitest-command-line:1.30.1",
            "org.pitest:pitest-junit5-plugin:1.2.3");

    private final GtaPaths paths;
    private final GtaConfig config;
    private final Git git;
    private final String javaExecutable;

    /** PIT runs from the Maven test classpath; a Gradle build gets no mutation result yet (plan §15 P3). */
    static final String GRADLE_UNSUPPORTED = "mutation testing needs a Maven build; skipped for this Gradle build";

    public MutationRunner(GtaPaths paths, GtaConfig config) {
        // PIT runs on the JVM gtaw runs on (17+); the service's bytecode, even Java 8, runs there too
        this(paths, config, ProcessHandle.current().info().command().orElse("java"));
    }

    MutationRunner(GtaPaths paths, GtaConfig config, String javaExecutable) {
        this.paths = paths;
        this.config = config;
        this.git = new Git(paths.root());
        this.javaExecutable = javaExecutable;
    }

    /** One PIT mutant. */
    public record Mutant(
            String sourceFile, String mutatedClass, String method, int line, String mutator, String status) {
        boolean covered() {
            return !"NO_COVERAGE".equals(status);
        }

        boolean killed() {
            return "KILLED".equals(status) || "TIMED_OUT".equals(status) || "MEMORY_ERROR".equals(status);
        }
    }

    public Map<String, Object> changed(String base) {
        Map<String, Set<Integer>> lines = changedLines(base);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("base", base);
        out.put("classes", List.copyOf(lines.keySet()));
        if (lines.isEmpty()) {
            out.put("result", "no production class changed since " + base);
            return out;
        }
        if (GradleRunner.selected(paths, config)) {
            out.put("result", GRADLE_UNSUPPORTED);
            return out;
        }
        Path work = paths.work().resolve("pit");
        List<Mutant> all = run(work, lines.keySet(), List.of("*Test"));
        List<Mutant> onChangedLines = all.stream()
                .filter(m -> lines.getOrDefault(m.mutatedClass(), Set.of()).contains(m.line()))
                .toList();
        return summary(out, onChangedLines);
    }

    static Map<String, Object> summary(Map<String, Object> out, List<Mutant> mutants) {
        long covered = mutants.stream().filter(Mutant::covered).count();
        long killed = mutants.stream().filter(Mutant::killed).count();
        out.put("mutants", mutants.size());
        out.put("covered", covered);
        out.put("killed", killed);
        out.put("strength", covered == 0 ? "n/a" : Math.round(100.0 * killed / covered) + "%");
        out.put(
                "survived",
                mutants.stream()
                        .filter(m -> m.covered() && !m.killed())
                        .map(m -> m.mutatedClass() + ":" + m.line() + " " + m.method() + " ("
                                + shortMutator(m.mutator()) + ")")
                        .toList());
        out.put(
                "notCovered",
                mutants.stream()
                        .filter(m -> !m.covered())
                        .map(m -> m.mutatedClass() + ":" + m.line())
                        .distinct()
                        .toList());
        out.put(
                "result",
                mutants.isEmpty()
                        ? "no mutant on the changed lines"
                        : covered == 0
                                ? "no unit or slice test covers the changed lines"
                                : killed + " of " + covered + " covered mutants killed (advisory)");
        return out;
    }

    private static String shortMutator(String mutator) {
        return mutator.substring(mutator.lastIndexOf('.') + 1);
    }

    /** Changed main classes of the service module and their changed line numbers. */
    Map<String, Set<Integer>> changedLines(String base) {
        Set<String> files = new LinkedHashSet<>(git.changedFiles(base));
        files.addAll(git.dirtyPaths());
        String prefix = paths.inModule(GtaPaths.MAIN_JAVA + "/");
        Map<String, Set<Integer>> out = new LinkedHashMap<>();
        for (String f : files) {
            if (f.startsWith(prefix)
                    && f.endsWith(".java")
                    && Files.exists(paths.root().resolve(f))) {
                Set<Integer> lines = git.changedLines(base, f);
                if (!lines.isEmpty()) {
                    out.put(
                            f.substring(prefix.length(), f.length() - ".java".length())
                                    .replace('/', '.'),
                            lines);
                }
            }
        }
        return out;
    }

    /** Outcome of the can-fail check of one item. */
    public record CanFail(String verdict, int mutants, int killed, String detail) {
        public static final String KILLED = "killed";
        public static final String SURVIVED = "survived";
        public static final String NOT_APPLICABLE = "n/a";
    }

    /**
     * The can-fail check (plan §5.6 item 5): mutate the methods the scenario's spec cites and run only the item's own
     * test. The test can fail when at least one mutant of those methods is killed; when every covered mutant survives,
     * the test passes whatever the code does. {@code n/a} when PIT finds no covered mutant in those methods.
     */
    public CanFail canFail(String itemId, Map<String, Set<String>> methodsByClass, String testClass) {
        if (methodsByClass.isEmpty()) {
            return new CanFail(CanFail.NOT_APPLICABLE, 0, 0, "the scenario cites no method");
        }
        if (GradleRunner.selected(paths, config)) {
            return new CanFail(CanFail.NOT_APPLICABLE, 0, 0, GRADLE_UNSUPPORTED);
        }
        Path work = paths.work().resolve("pit").resolve(itemId.replaceAll("[^A-Za-z0-9.-]", "_"));
        List<Mutant> mutants = run(work, methodsByClass.keySet(), List.of(testClass)).stream()
                .filter(m ->
                        methodsByClass.getOrDefault(m.mutatedClass(), Set.of()).contains(m.method()))
                .filter(Mutant::covered)
                .toList();
        return verdict(mutants, testClass);
    }

    static CanFail verdict(List<Mutant> covered, String testClass) {
        if (covered.isEmpty()) {
            return new CanFail(
                    CanFail.NOT_APPLICABLE, 0, 0, "no mutant of the cited methods is reached by " + testClass);
        }
        int killed = (int) covered.stream().filter(Mutant::killed).count();
        return killed > 0
                ? new CanFail(
                        CanFail.KILLED, covered.size(), killed, killed + " of " + covered.size() + " mutants killed")
                : new CanFail(
                        CanFail.SURVIVED,
                        covered.size(),
                        0,
                        "none of " + covered.size() + " mutants of "
                                + covered.stream()
                                        .map(m -> m.mutatedClass()
                                                        .substring(
                                                                m.mutatedClass().lastIndexOf('.') + 1) + "#"
                                                + m.method())
                                        .distinct()
                                        .toList()
                                + " made " + testClass + " fail: it passes whatever the code does");
    }

    private List<Mutant> run(Path work, Set<String> classes, List<String> targetTests) {
        Path lib = work.resolve("lib");
        Path classpathFile = work.resolve("classpath.txt");
        Path report = work.resolve("report");
        try {
            Files.createDirectories(lib);
        } catch (IOException e) {
            throw new GtaException(ErrorCode.ENV_TOOL_UNAVAILABLE, "Cannot create " + lib, "Check the disk", e);
        }
        MavenRunner maven = new MavenRunner(paths, config);
        List<String> prepare = maven.mavenCommand();
        prepare.addAll(List.of("-B", "-q"));
        prepare.addAll(MavenRunner.moduleArgs(paths.module()));
        prepare.addAll(List.of(
                "test-compile",
                "dependency:build-classpath",
                "-Dmdep.includeScope=test",
                "-Dmdep.outputFile=" + classpathFile.toAbsolutePath()));
        mustSucceed(prepare, "compile the service and list its test classpath");
        String testClasspath;
        try {
            testClasspath =
                    Files.readString(classpathFile, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new GtaException(
                    ErrorCode.ENV_TOOL_UNAVAILABLE,
                    "Cannot read the test classpath: " + e.getMessage(),
                    "Run again",
                    e);
        }
        List<String> artifacts = new ArrayList<>(PIT_ARTIFACTS);
        launcherFor(testClasspath).ifPresent(artifacts::add);
        // PIT and its own dependencies, resolved by Maven (the service's repositories and settings) from a tools pom
        Path toolsPom = work.resolve("pit-tools.pom.xml");
        try {
            com.gta.core.util.AtomicFiles.write(toolsPom, toolsPom(artifacts));
        } catch (RuntimeException e) {
            throw new GtaException(ErrorCode.ENV_TOOL_UNAVAILABLE, "Cannot write " + toolsPom, "Check the disk", e);
        }
        List<String> copy = maven.mavenCommand();
        copy.addAll(List.of(
                "-B",
                "-q",
                "-f",
                toolsPom.toAbsolutePath().toString(),
                "dependency:copy-dependencies",
                "-DoutputDirectory=" + lib.toAbsolutePath()));
        mustSucceed(copy, "download PIT through the Maven repositories of the service");
        String classpath;
        try (Stream<Path> jars = Files.list(lib)) {
            List<String> parts = new ArrayList<>(
                    jars.map(p -> p.toAbsolutePath().toString()).sorted().toList());
            parts.add(paths.moduleRoot()
                    .resolve("target/classes")
                    .toAbsolutePath()
                    .toString());
            parts.add(paths.moduleRoot()
                    .resolve("target/test-classes")
                    .toAbsolutePath()
                    .toString());
            parts.add(testClasspath);
            classpath = String.join(File.pathSeparator, parts);
        } catch (IOException e) {
            throw new GtaException(
                    ErrorCode.ENV_TOOL_UNAVAILABLE, "Cannot read the PIT classpath: " + e.getMessage(), "Run again", e);
        }
        List<String> pit = List.of(
                javaExecutable,
                "-cp",
                classpath,
                "org.pitest.mutationtest.commandline.MutationCoverageReport",
                "--reportDir",
                report.toAbsolutePath().toString(),
                "--targetClasses",
                String.join(",", classes),
                "--targetTests",
                String.join(",", targetTests),
                "--excludedTestClasses",
                "*E2ETest,*IT",
                "--sourceDirs",
                paths.mainJava().toAbsolutePath().toString(),
                "--outputFormats",
                "XML",
                "--timestampedReports=false",
                "--threads",
                "2");
        mustSucceed(pit, "run PIT");
        return parse(report.resolve("mutations.xml"));
    }

    /** A pom that only lists the tools; never the service's own pom, so nothing of the service changes. */
    static String toolsPom(List<String> artifacts) {
        StringBuilder deps = new StringBuilder();
        for (String a : artifacts) {
            String[] g = a.split(":");
            deps.append("    <dependency><groupId>")
                    .append(g[0])
                    .append("</groupId><artifactId>")
                    .append(g[1])
                    .append("</artifactId><version>")
                    .append(g[2])
                    .append("</version></dependency>\n");
        }
        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.gta.work</groupId>\n  <artifactId>pit-tools</artifactId>\n  <version>1</version>\n"
                + "  <packaging>pom</packaging>\n  <dependencies>\n" + deps + "  </dependencies>\n</project>\n";
    }

    private static final java.util.regex.Pattern PLATFORM_ENGINE =
            java.util.regex.Pattern.compile("junit-platform-engine-([0-9][^"
                    + java.util.regex.Pattern.quote(File.separator) + File.pathSeparator + "]*?)\\.jar");

    /**
     * The JUnit Platform launcher PIT needs, at the version of the service's own platform. Surefire adds the launcher
     * at run time, so it is rarely on the test classpath; without it PIT's test discovery fails.
     */
    static java.util.Optional<String> launcherFor(String testClasspath) {
        if (testClasspath.contains("junit-platform-launcher-")) {
            return java.util.Optional.empty();
        }
        java.util.regex.Matcher m = PLATFORM_ENGINE.matcher(testClasspath);
        return m.find()
                ? java.util.Optional.of("org.junit.platform:junit-platform-launcher:" + m.group(1))
                : java.util.Optional.empty();
    }

    private void mustSucceed(List<String> command, String what) {
        ProcessRunner.Result r = ProcessRunner.run(
                paths.root(), Duration.ofMinutes(config.budgets.nodeTimeoutMinutes), Map.of(), command);
        if (!r.ok()) {
            String out = r.output();
            throw new GtaException(
                    ErrorCode.ENV_TOOL_UNAVAILABLE,
                    "Could not " + what + ": " + out.substring(Math.max(0, out.length() - 1500)),
                    "Fix the build first (./gtaw run), then run gtaw mutate again");
        }
    }

    /** PIT's mutations.xml; a missing report means no mutant was generated. */
    static List<Mutant> parse(Path xml) {
        List<Mutant> out = new ArrayList<>();
        if (!Files.exists(xml)) {
            return out;
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            NodeList list = f.newDocumentBuilder().parse(xml.toFile()).getElementsByTagName("mutation");
            for (int i = 0; i < list.getLength(); i++) {
                Element e = (Element) list.item(i);
                out.add(new Mutant(
                        text(e, "sourceFile"),
                        text(e, "mutatedClass"),
                        text(e, "mutatedMethod"),
                        Integer.parseInt(text(e, "lineNumber")),
                        text(e, "mutator"),
                        e.getAttribute("status")));
            }
            return out;
        } catch (Exception e) {
            throw new GtaException(
                    ErrorCode.ENV_TEST_REPORTS_UNUSABLE,
                    "Cannot read PIT's report " + xml + ": " + e.getMessage(),
                    "Run gtaw mutate again");
        }
    }

    private static String text(Element e, String tag) {
        NodeList n = e.getElementsByTagName(tag);
        return n.getLength() == 0 ? "" : n.item(0).getTextContent().strip();
    }
}
