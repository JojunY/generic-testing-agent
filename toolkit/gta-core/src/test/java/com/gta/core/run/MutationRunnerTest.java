package com.gta.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.error.GtaException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** gtaw mutate (plan §15 P2): PIT's report, counted on the changed lines. */
class MutationRunnerTest {
    @TempDir
    Path dir;

    private static String mutation(String status, int line, String method) {
        return "<mutation detected='" + !"SURVIVED".equals(status) + "' status='" + status + "'>"
                + "<sourceFile>PricingService.java</sourceFile>"
                + "<mutatedClass>com.example.order.service.PricingService</mutatedClass>"
                + "<mutatedMethod>" + method + "</mutatedMethod>"
                + "<lineNumber>" + line + "</lineNumber>"
                + "<mutator>org.pitest.mutationtest.engine.gregor.mutators.ConditionalsBoundaryMutator</mutator>"
                + "</mutation>";
    }

    @Test
    void shouldReadPitsReport() throws Exception {
        Path xml = dir.resolve("mutations.xml");
        Files.writeString(
                xml,
                "<?xml version='1.0'?><mutations>" + mutation("KILLED", 20, "discount")
                        + mutation("SURVIVED", 21, "discount") + mutation("NO_COVERAGE", 30, "rate") + "</mutations>");

        List<MutationRunner.Mutant> mutants = MutationRunner.parse(xml);

        assertEquals(3, mutants.size());
        assertEquals("com.example.order.service.PricingService", mutants.get(0).mutatedClass());
        assertEquals(21, mutants.get(1).line());
        assertEquals("SURVIVED", mutants.get(1).status());
        assertEquals(List.of(), MutationRunner.parse(dir.resolve("none.xml")), "no report: no mutant was generated");
    }

    @Test
    void shouldRefuseAReportWithADoctype() throws Exception {
        Path xml = dir.resolve("mutations.xml");
        Files.writeString(
                xml, "<?xml version='1.0'?><!DOCTYPE m [<!ENTITY x SYSTEM 'file:///etc/hostname'>]><mutations/>");

        assertThrows(GtaException.class, () -> MutationRunner.parse(xml));
    }

    @Test
    void shouldCountKilledAmongCoveredMutantsAndNameTheSurvivors() {
        List<MutationRunner.Mutant> mutants = List.of(
                new MutationRunner.Mutant("P.java", "a.P", "discount", 20, "x.ConditionalsBoundaryMutator", "KILLED"),
                new MutationRunner.Mutant("P.java", "a.P", "discount", 21, "x.MathMutator", "SURVIVED"),
                new MutationRunner.Mutant("P.java", "a.P", "rate", 30, "x.MathMutator", "NO_COVERAGE"),
                new MutationRunner.Mutant("P.java", "a.P", "slow", 40, "x.MathMutator", "TIMED_OUT"));

        Map<String, Object> out = MutationRunner.summary(new LinkedHashMap<>(), mutants);

        assertEquals(3L, out.get("covered"));
        assertEquals(2L, out.get("killed"), "a timeout counts as killed");
        assertEquals("67%", out.get("strength"));
        assertEquals(List.of("a.P:21 discount (MathMutator)"), out.get("survived"));
        assertEquals(List.of("a.P:30"), out.get("notCovered"));
    }

    @Test
    void shouldSayWhenNoUnitOrSliceTestCoversTheChange() {
        Map<String, Object> out = MutationRunner.summary(
                new LinkedHashMap<>(),
                List.of(new MutationRunner.Mutant("P.java", "a.P", "m", 1, "x.MathMutator", "NO_COVERAGE")));

        assertEquals("n/a", out.get("strength"));
        assertTrue(String.valueOf(out.get("result")).contains("no unit or slice test"), out.toString());
    }

    @Test
    void shouldAddTheJunitLauncherAtTheServicesPlatformVersion() {
        String sep = java.io.File.pathSeparator;
        String cp = "/m2/org/junit/platform/junit-platform-engine/6.0.3/junit-platform-engine-6.0.3.jar" + sep
                + "/m2/org/assertj/assertj-core/3.27.7/assertj-core-3.27.7.jar";

        assertEquals(
                java.util.Optional.of("org.junit.platform:junit-platform-launcher:6.0.3"),
                MutationRunner.launcherFor(cp));
        assertEquals(
                java.util.Optional.empty(),
                MutationRunner.launcherFor(cp + sep + "/m2/junit-platform-launcher-6.0.3.jar"),
                "already there");
        assertEquals(java.util.Optional.empty(), MutationRunner.launcherFor("/m2/junit-4.13.jar"), "no JUnit Platform");
    }

    @Test
    void shouldListTheToolsInAPomOfTheirOwn() {
        String pom = MutationRunner.toolsPom(
                List.of("org.pitest:pitest:1.30.1", "org.junit.platform:junit-platform-launcher:6.0.3"));

        assertTrue(
                pom.contains("<groupId>org.pitest</groupId><artifactId>pitest</artifactId><version>1.30.1</version>"),
                pom);
        assertTrue(pom.contains("<artifactId>junit-platform-launcher</artifactId><version>6.0.3</version>"), pom);
        assertTrue(pom.contains("<packaging>pom</packaging>"));
    }

    private void git(Path root, String... args) {
        List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        com.gta.core.util.ProcessRunner.run(root, java.time.Duration.ofSeconds(30), Map.of(), cmd);
    }

    private static Path script(Path dir, String name, String body) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        return f;
    }

    @Test
    void shouldMutateOnlyTheChangedLinesWithTheToolsMavenResolved() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                com.gta.core.util.ProcessRunner.isWindows(), "the stand-ins for Maven and java are shell scripts");
        Path repo = dir.resolve("repo");
        Path source = repo.resolve("src/main/java/a/P.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package a;\nclass P {\n  int f() { return 1; }\n}\n");
        git(repo, "init", "-q");
        git(repo, "-c", "user.email=t@e", "-c", "user.name=t", "add", "-A");
        git(repo, "-c", "user.email=t@e", "-c", "user.name=t", "commit", "-qm", "base");
        git(repo, "branch", "base");
        Files.writeString(source, "package a;\nclass P {\n  int f() { return 2; }\n}\n");

        com.gta.core.config.GtaConfig config = new com.gta.core.config.GtaConfig();
        // Maven: writes the test classpath and "downloads" the tools; java: writes PIT's report
        config.build.maven = script(dir, "mvn", """
                for a in "$@"; do
                  case "$a" in
                    -Dmdep.outputFile=*) echo "/m2/junit-platform-engine-6.0.3.jar" > "${a#*=}" ;;
                    -DoutputDirectory=*) mkdir -p "${a#*=}" && touch "${a#*=}/pitest.jar" ;;
                  esac
                done
                """).toString();
        Path java = script(dir, "java", """
                while [ "$1" != "--reportDir" ]; do shift; done
                mkdir -p "$2" && cat > "$2/mutations.xml" <<'XML'
                <mutations>
                <mutation status='KILLED'><sourceFile>P.java</sourceFile><mutatedClass>a.P</mutatedClass><mutatedMethod>f</mutatedMethod><lineNumber>3</lineNumber><mutator>x.MathMutator</mutator></mutation>
                <mutation status='SURVIVED'><sourceFile>P.java</sourceFile><mutatedClass>a.P</mutatedClass><mutatedMethod>g</mutatedMethod><lineNumber>9</lineNumber><mutator>x.MathMutator</mutator></mutation>
                </mutations>
                XML
                """);

        Map<String, Object> out =
                new MutationRunner(new com.gta.core.config.GtaPaths(repo), config, java.toString()).changed("base");

        assertEquals(List.of("a.P"), out.get("classes"));
        assertEquals(1, out.get("mutants"), "the survivor on an unchanged line is not counted: " + out);
        assertEquals("100%", out.get("strength"));
        assertTrue(Files.readString(repo.resolve(".gta/work/pit/pit-tools.pom.xml"))
                .contains("<artifactId>junit-platform-launcher</artifactId><version>6.0.3</version>"));

        assertTrue(
                String.valueOf(new MutationRunner(new com.gta.core.config.GtaPaths(repo), config, java.toString())
                                .changed("HEAD")
                                .get("classes"))
                        .contains("a.P"),
                "uncommitted changes count too");

        Files.writeString(dir.resolve("mvn"), "#!/bin/sh\necho 'BUILD FAILURE'; exit 1\n");
        GtaException broken = assertThrows(
                GtaException.class,
                () -> new MutationRunner(new com.gta.core.config.GtaPaths(repo), config, java.toString())
                        .changed("base"));
        assertEquals(com.gta.core.error.ErrorCode.ENV_TOOL_UNAVAILABLE, broken.code(), "a broken build is reported");
    }

    @Test
    void shouldSayWhenNoProductionClassChanged() throws Exception {
        Path repo = dir.resolve("empty");
        Files.createDirectories(repo);
        git(repo, "init", "-q");
        Files.writeString(repo.resolve("README.md"), "x");
        git(repo, "-c", "user.email=t@e", "-c", "user.name=t", "add", "-A");
        git(repo, "-c", "user.email=t@e", "-c", "user.name=t", "commit", "-qm", "base");

        Map<String, Object> out = new MutationRunner(
                        new com.gta.core.config.GtaPaths(repo), new com.gta.core.config.GtaConfig())
                .changed("HEAD");

        assertTrue(String.valueOf(out.get("result")).startsWith("no production class changed"), out.toString());
    }

    @Test
    void shouldJudgeWhetherATestCanFailFromTheMutantsOfTheCitedMethods() {
        MutationRunner.Mutant killed =
                new MutationRunner.Mutant("L.java", "a.L", "on", 5, "x.VoidMethodCallMutator", "KILLED");
        MutationRunner.Mutant survived =
                new MutationRunner.Mutant("L.java", "a.L", "on", 5, "x.VoidMethodCallMutator", "SURVIVED");

        assertEquals(
                MutationRunner.CanFail.KILLED,
                MutationRunner.verdict(List.of(killed, survived), "a.LTest").verdict());
        MutationRunner.CanFail weak = MutationRunner.verdict(List.of(survived), "a.LTest");
        assertEquals(MutationRunner.CanFail.SURVIVED, weak.verdict());
        assertTrue(
                weak.detail().contains("L#on") && weak.detail().contains("passes whatever the code does"),
                weak.detail());
        assertEquals(
                MutationRunner.CanFail.NOT_APPLICABLE,
                MutationRunner.verdict(List.of(), "a.LTest").verdict());
    }

    @Test
    void shouldNotJudgeAScenarioThatCitesNoMethod() {
        assertEquals(
                MutationRunner.CanFail.NOT_APPLICABLE,
                new MutationRunner(new com.gta.core.config.GtaPaths(dir), new com.gta.core.config.GtaConfig())
                        .canFail("i", Map.of(), "a.LTest")
                        .verdict());
    }

    @Test
    void shouldSkipMutationTestingForAGradleBuild() throws Exception {
        Files.writeString(dir.resolve("build.gradle"), "plugins { id 'java' }");
        MutationRunner runner =
                new MutationRunner(new com.gta.core.config.GtaPaths(dir), new com.gta.core.config.GtaConfig());

        MutationRunner.CanFail r = runner.canFail("i", Map.of("a.L", Set.of("m")), "a.LTest");

        assertEquals(MutationRunner.CanFail.NOT_APPLICABLE, r.verdict());
        assertEquals(MutationRunner.GRADLE_UNSUPPORTED, r.detail());
    }
}
