package com.gta.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.gta.core.config.GtaConfig;
import com.gta.core.config.GtaPaths;
import com.gta.core.util.ProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test runs through Gradle (plan §15 P3): an init script selects the scenario and passes gta-junit its results
 * directory, so the service's build script stays untouched. A shell script stands in for Gradle and writes the JUnit
 * XML a Gradle Test task writes (testcases named by display name, messages prefixed with the exception type).
 */
class GradleRunnerTest {
    @TempDir
    Path root;

    GtaPaths paths;
    GtaConfig config;

    @BeforeEach
    void setUp() {
        paths = new GtaPaths(root);
        config = new GtaConfig();
    }

    private void script(String body) throws Exception {
        assumeFalse(ProcessRunner.isWindows(), "the stand-in for Gradle is a POSIX shell script");
        Path gradle = root.resolve("fake-gradle");
        Files.writeString(gradle, "#!/bin/sh\necho \"$@\" > args.txt\n" + body);
        Files.setPosixFilePermissions(gradle, PosixFilePermissions.fromString("rwxr-xr-x"));
        config.build.gradle = gradle.toString();
    }

    private static final String REPORT = """
            mkdir -p build/test-results/test target/gta-results
            cat > build/test-results/test/TEST-com.acme.QuoteIT.xml <<'XML'
            <testsuite name="com.acme.QuoteIT">
              <testcase classname="com.acme.QuoteIT" name="[quote/s01] a small amount"/>
              <testcase classname="com.acme.QuoteIT" name="[quote/s02] the threshold"><failure type="java.lang.AssertionError" message="java.lang.AssertionError: expected:&lt;90.0&gt; but was:&lt;100.0&gt;">java.lang.AssertionError
                at com.acme.QuoteIT.shouldDiscount(QuoteIT.java:20)</failure></testcase>
            </testsuite>
            XML
            cat > target/gta-results/results-1.jsonl <<'JSON'
            {"className":"com.acme.QuoteIT","method":"shouldDiscount","displayName":"[quote/s02] the threshold","tags":["scenario:quote/s02"],"outcome":"failed"}
            JSON
            exit 1
            """;

    @Test
    void shouldRunOneScenarioThroughAnInitScriptAndReadGradleReports() throws Exception {
        script(REPORT);

        RunResult r = new GradleRunner(paths, config)
                .run(
                        "r1",
                        "quote/s02#component-01",
                        new TestRunner.Options("com.acme.QuoteIT", "scenario:quote/s02", false, false),
                        Set.of("com.acme.Quote"),
                        Set.of("com.acme.QuoteIT"));

        String args = Files.readString(root.resolve("args.txt")).strip();
        Path init = paths.work().resolve("gradle/gta-init.gradle");
        assertTrue(args.contains("-I " + init.toAbsolutePath()), args);
        assertTrue(args.contains("-Pgta.groups=scenario:quote/s02"), args);
        assertTrue(args.endsWith("test --tests com.acme.QuoteIT"), args);
        assertTrue(Files.readString(init).contains("includeTags"), "the init script selects the scenario tag");
        assertEquals(2, r.cases.size());
        TestOutcome failed = r.failures().get(0);
        assertEquals("TEST_ASSERTION_FAILED", failed.code);
        assertEquals("expected:<90.0> but was:<100.0>", failed.message, "the type prefix Gradle adds is removed");
        assertEquals("shouldDiscount", failed.method, "the method comes from gta-junit's results");
        assertTrue(failed.tags.contains("scenario:quote/s02"));
        assertTrue(Files.exists(paths.runs().resolve("r1/build.log")), "the log is kept as evidence");
    }

    @Test
    void shouldRunEveryTestWithoutATagOrClassFilter() throws Exception {
        script("exit 0\n");

        new GradleRunner(paths, config)
                .run("r2", null, new TestRunner.Options(null, null, false, false), Set.of(), Set.of());

        String args = Files.readString(root.resolve("args.txt")).strip();
        assertTrue(args.endsWith(" test"), args);
        assertFalse(args.contains("--tests"), args);
        assertFalse(args.contains("gta.groups"), args);
    }

    @Test
    void shouldReportACompileErrorWithItsLocation() throws Exception {
        script("""
                echo "/work/src/test/java/com/acme/QuoteIT.java:12: error: cannot find symbol"
                echo "Compilation failed; see the compiler error output for details."
                exit 1
                """);

        RunResult r = new GradleRunner(paths, config)
                .run("r3", null, new TestRunner.Options("X", null, false, false), Set.of(), Set.of());

        assertTrue(r.compileError);
        assertEquals("src/test/java/com/acme/QuoteIT.java:12 cannot find symbol", r.compileMessage);
        assertEquals(
                "compilation failed (see build.log)",
                new GradleRunner(paths, config).compileErrors("Compilation failed"));
    }

    @Test
    void shouldRunTheTestTaskOfTheServiceSubproject() {
        assertEquals("test", GradleRunner.testTask(null));
        assertEquals("test", GradleRunner.testTask(""));
        assertEquals(":services:order-api:test", GradleRunner.testTask("services/order-api"));
    }

    @Test
    void shouldPreferTheGradleWrapper() throws Exception {
        assumeFalse(ProcessRunner.isWindows(), "the wrapper name differs on Windows");
        assertEquals(List.of("gradle"), new GradleRunner(paths, config).gradleCommand());
        Files.writeString(root.resolve("gradlew"), "");
        assertEquals(List.of("sh", "gradlew"), new GradleRunner(paths, config).gradleCommand());
    }

    @Test
    void shouldSelectGradleByTheBuildToolOrTheBuildFileTheServiceHas() throws Exception {
        assertFalse(GradleRunner.selected(paths, config), "no build file: Maven, which then reports the missing pom");
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }");
        assertTrue(GradleRunner.selected(paths, config));
        assertInstanceOf(GradleRunner.class, TestRunner.forBuild(paths, config));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        assertFalse(GradleRunner.selected(paths, config), "a pom.xml wins when both exist");
        config.build.tool = "gradle";
        assertTrue(GradleRunner.selected(paths, config));
        config.build.tool = "maven";
        Files.delete(root.resolve("pom.xml"));
        assertFalse(GradleRunner.selected(paths, config));
        assertInstanceOf(MavenRunner.class, TestRunner.forBuild(paths, config));
    }
}
