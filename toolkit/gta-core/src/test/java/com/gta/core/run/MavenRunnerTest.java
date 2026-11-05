package com.gta.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Test runs through Maven (plan §5.3): gtaw, not the agent, decides what passed. A shell script stands in for Maven,
 * records its arguments and environment, and writes the reports a real Surefire run would.
 */
class MavenRunnerTest {
    @TempDir
    Path root;

    GtaPaths paths;
    GtaConfig config;

    @BeforeEach
    void fakeMaven() {
        paths = new GtaPaths(root);
        config = new GtaConfig();
    }

    private void script(String body) throws Exception {
        assumeFalse(ProcessRunner.isWindows(), "the stand-in for Maven is a POSIX shell script");
        Path mvn = root.resolve("fake-mvn");
        Files.writeString(mvn, "#!/bin/sh\necho \"$@\" > args.txt\necho \"$JAVA_TOOL_OPTIONS\" > env.txt\n" + body);
        Files.setPosixFilePermissions(mvn, PosixFilePermissions.fromString("rwxr-xr-x"));
        config.build.maven = mvn.toString();
    }

    private static final String REPORT = """
            mkdir -p target/surefire-reports
            cat > target/surefire-reports/TEST-com.acme.OrderControllerIT.xml <<'XML'
            <testsuite name="com.acme.OrderControllerIT">
              <testcase classname="com.acme.OrderControllerIT" name="shouldCreate()"/>
              <testcase classname="com.acme.OrderControllerIT" name="shouldReject()"><failure type="java.lang.AssertionError" message="expected: &lt;400&gt; but was: &lt;201&gt;">java.lang.AssertionError
                at com.acme.OrderControllerIT.shouldReject(OrderControllerIT.java:20)</failure></testcase>
            </testsuite>
            XML
            exit 1
            """;

    @Test
    void shouldRunOneScenarioAndClassifyItsFailure() throws Exception {
        script(REPORT);

        RunResult r = new MavenRunner(paths, config)
                .run(
                        "r1",
                        "order.create/s01#component-01",
                        new TestRunner.Options("com.acme.OrderControllerIT", "scenario:order.create/s01", false, false),
                        Set.of("com.acme.Order"),
                        Set.of("com.acme.OrderControllerIT"));

        String args = Files.readString(root.resolve("args.txt"));
        assertTrue(args.contains("-Dtest=com.acme.OrderControllerIT"), args);
        assertTrue(args.contains("-Dgroups=scenario:order.create/s01"), args);
        assertTrue(args.endsWith("test\n"), args);
        assertEquals(2, r.cases.size());
        assertEquals(
                "TEST_ASSERTION_FAILED", r.failures().get(0).code, "a <failure> is an assertion that did not hold");
        assertEquals(1, r.exitCode);
        assertFalse(r.timedOut);
        assertTrue(Files.exists(paths.runs().resolve("r1/maven.log")), "the log is kept as evidence");
        assertTrue(Files.exists(paths.runs().resolve("r1/run.json")));
    }

    @Test
    void shouldSelectEveryGtaTestByItsSuffixAndPassTheStabilityVariant() throws Exception {
        script("exit 0\n");

        new MavenRunner(paths, config)
                .run("r2", null, new TestRunner.Options(null, "gta", true, true), Set.of(), Set.of());

        assertTrue(Files.readString(root.resolve("args.txt")).contains("-Dtest=" + MavenRunner.ALL_TESTS));
        String env = Files.readString(root.resolve("env.txt"));
        assertTrue(env.contains("-Duser.timezone=Asia/Kolkata"), env);
        assertTrue(env.contains("MethodOrderer$Random"), env);
    }

    @Test
    void shouldReportACompileErrorWithItsLocation() throws Exception {
        script("""
                echo "[ERROR] COMPILATION ERROR :"
                echo "[ERROR] /work/src/test/java/com/acme/OrderControllerIT.java:[12,5] cannot find symbol"
                exit 1
                """);

        RunResult r = new MavenRunner(paths, config)
                .run("r3", null, new TestRunner.Options("X", null, false, false), Set.of(), Set.of());

        assertTrue(r.compileError);
        assertEquals("src/test/java/com/acme/OrderControllerIT.java:12 cannot find symbol", r.compileMessage);
        assertEquals("compilation failed (see maven.log)", MavenRunner.compileSummary("BUILD FAILURE"));
    }

    @Test
    void shouldCountAHangingRunAsTimedOut() throws Exception {
        script("sleep 120\n");
        config.budgets.nodeTimeoutMinutes = 0; // the run is cut at once

        RunResult r = new MavenRunner(paths, config)
                .run("r4", null, new TestRunner.Options("X", null, false, false), Set.of(), Set.of());

        assertTrue(r.timedOut, "a hanging test is a result the item's budget counts, not an error");
    }

    @Test
    void shouldPreferTheMavenWrapper() throws Exception {
        assumeFalse(ProcessRunner.isWindows());
        assertEquals(List.of("mvn"), new MavenRunner(paths, config).mavenCommand());
        Files.writeString(root.resolve("mvnw"), "");
        assertEquals(List.of("sh", "mvnw"), new MavenRunner(paths, config).mavenCommand());
        config.build.maven = "/opt/maven/bin/mvn";
        assertEquals(List.of("/opt/maven/bin/mvn"), new MavenRunner(paths, config).mavenCommand());
    }

    @Test
    void shouldRunTheStabilityVariantInTheConfiguredTimezoneAndLocale() {
        GtaConfig.QualityGates gates = new GtaConfig.QualityGates();

        assertEquals(
                "-Duser.timezone=Asia/Kolkata -Duser.language=de -Duser.country=DE",
                MavenRunner.stabilityVariantOptions(gates));
        assertEquals("Asia/Kolkata timezone / de-DE locale", MavenRunner.stabilityVariantLabel(gates));
        gates.stabilityTimezone = "Pacific/Chatham";
        gates.stabilityLocale = "tr";
        assertEquals("-Duser.timezone=Pacific/Chatham -Duser.language=tr", MavenRunner.stabilityVariantOptions(gates));
    }
}
