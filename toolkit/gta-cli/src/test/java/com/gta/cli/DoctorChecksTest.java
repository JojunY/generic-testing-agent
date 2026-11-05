package com.gta.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DoctorChecksTest {
    @Test
    void shouldRunDependencyChecksOnlyForServicesThatUseTheDependency() {
        List<String> deps = List.of("spring-boot-starter-web", "ojdbc11", "spring-cloud-gcp-starter-pubsub");

        assertTrue(DoctorChecks.uses(deps, "ojdbc"));
        assertTrue(DoctorChecks.uses(deps, "pubsub"));
        assertFalse(DoctorChecks.uses(List.of("spring-boot-starter-web"), "ojdbc"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> check(Path root, String name) {
        List<Map<String, String>> checks =
                (List<Map<String, String>>) new DoctorChecks(root).run().get("checks");
        return checks.stream()
                .filter(c -> name.equals(c.get("check")))
                .findFirst()
                .orElseThrow();
    }

    private static Path pom(Path root, String modules) throws Exception {
        Files.writeString(
                root.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>a</groupId><artifactId>b</artifactId>"
                        + "<version>1</version>" + modules + "</project>");
        return root;
    }

    @Test
    void shouldAskForTheServiceModuleOfAMultiModuleRepository(@TempDir Path root) throws Exception {
        Map<String, String> unset = check(pom(root, "<modules><module>api</module></modules>"), "modules");
        assertEquals("fail", unset.get("status"), "gtaw cannot work on an aggregator pom");
        assertTrue(unset.get("hint").contains("build.module"), unset.toString());

        Files.createDirectories(root.resolve("api"));
        Files.writeString(root.resolve("api/pom.xml"), "<project><artifactId>api</artifactId></project>");
        Files.createDirectories(root.resolve(".gta"));
        Files.writeString(root.resolve(".gta/config.json"), "{\"build\":{\"module\":\"api\"}}");
        Map<String, String> set = check(root, "modules");
        assertEquals("ok", set.get("status"));
        assertTrue(set.get("detail").contains("module api"), set.toString());
    }

    @Test
    void shouldAcceptASingleModuleService(@TempDir Path root) throws Exception {
        assertEquals("ok", check(pom(root, ""), "modules").get("status"));
    }

    private static String status(Path root, String name) {
        return check(root, name).get("status");
    }

    private static String pomWith(String parentVersion, String dependencies, String plugins) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>a</groupId><artifactId>b</artifactId><version>1</version>"
                + (parentVersion == null
                        ? ""
                        : "<parent><groupId>org.springframework.boot</groupId>"
                                + "<artifactId>spring-boot-starter-parent</artifactId><version>" + parentVersion
                                + "</version></parent>")
                + "<dependencies>" + dependencies + "</dependencies>"
                + "<build><plugins>" + plugins + "</plugins></build></project>";
    }

    private static String dep(String artifactId) {
        return "<dependency><groupId>x</groupId><artifactId>" + artifactId + "</artifactId></dependency>";
    }

    private static String plugin(String artifactId) {
        return "<plugin><artifactId>" + artifactId + "</artifactId></plugin>";
    }

    @Test
    void shouldFailWhenThereIsNoPom(@TempDir Path root) {
        Map<String, String> pom = check(root, "pom");

        assertEquals("fail", pom.get("status"));
        assertTrue(pom.containsKey("hint"), pom.toString());
    }

    @Test
    void shouldFailWhenTheDirectoryIsNotAGitWorkTree(@TempDir Path root) {
        assertEquals("fail", status(root, "git"));
    }

    @Test
    void shouldWarnForSpringBootOlderThanTheSupportedRange(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), pomWith("2.7.18", "", ""));
        assertEquals("warn", status(root, "spring-boot"));

        Files.writeString(root.resolve("pom.xml"), pomWith("3.5.0", "", ""));
        assertEquals("ok", status(root, "spring-boot"));
        assertTrue(check(root, "spring-boot").get("detail").contains("JUnit 5"));

        Files.writeString(root.resolve("pom.xml"), pomWith("4.1.0", "", ""));
        assertTrue(check(root, "spring-boot").get("detail").contains("JUnit 6"));
    }

    @Test
    void shouldReportWhetherGtaJunitAndFailsafeAreInThePom(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), pomWith("3.5.0", "", ""));
        assertEquals("warn", status(root, "gta-junit"));
        assertEquals("warn", status(root, "failsafe"));

        Files.writeString(root.resolve("pom.xml"), pomWith("3.5.0", dep("gta-junit"), plugin("maven-failsafe-plugin")));
        assertEquals("ok", status(root, "gta-junit"));
        assertEquals("ok", status(root, "failsafe"));
    }

    @Test
    void shouldCheckOracleOnlyForServicesThatUseOracleAndAskForTheTestSchema(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), pomWith("3.5.0", dep("spring-web"), ""));
        assertThrows(java.util.NoSuchElementException.class, () -> check(root, "oracle-test-db"));

        Files.writeString(root.resolve("pom.xml"), pomWith("3.5.0", dep("ojdbc11"), ""));
        assertEquals("warn", status(root, "oracle-test-db"));

        Files.createDirectories(root.resolve(".gta"));
        Files.writeString(
                root.resolve(".gta/config.json"),
                "{\"environments\":{\"oracle\":{\"testUrl\":\"jdbc:oracle:thin:@//db:1521/T\"}}}");
        assertEquals("ok", status(root, "oracle-test-db"));
    }

    @Test
    void shouldCheckPubSubToolsOnlyForServicesThatUsePubSub(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), pomWith("3.5.0", dep("spring-cloud-gcp-starter-pubsub"), ""));
        assertEquals("warn", status(root, "emulators"));

        Files.createDirectories(root.resolve(".gta"));
        Files.writeString(
                root.resolve(".gta/config.json"), "{\"distribution\":{\"toolsUrl\":\"https://n.example/raw\"}}");
        assertEquals("ok", status(root, "emulators"));
    }

    @Test
    void shouldFailOnAnInvalidConfigFile(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".gta"));
        Files.writeString(root.resolve(".gta/config.json"), "{\"noSuchKey\":1}");

        assertEquals("fail", status(root, "config"));
    }

    @Test
    void shouldFailUntilTheWrapperJarIsPinnedToARealUrlAndChecksum(@TempDir Path root) throws Exception {
        assertEquals("fail", status(root, "wrapper"));

        Path props = root.resolve(".gta/wrapper/gta-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "distributionUrl=https://nexus.example.com/gta-cli-1.jar\ndistributionSha256Sum=ab\n");
        assertEquals("fail", status(root, "wrapper"), "the example URL is a placeholder");

        Files.writeString(props, "distributionUrl=https://repo.acme.test/gta-cli-1.jar\n");
        assertEquals("fail", status(root, "wrapper"), "no checksum");

        Files.writeString(props, "distributionUrl=https://repo.acme.test/gta-cli-1.jar\ndistributionSha256Sum=ab\n");
        assertEquals("ok", status(root, "wrapper"));
    }

    @Test
    void shouldFailWhenTheHarnessIsNotInstalledAndWarnWhenManagedFilesChanged(@TempDir Path root) throws Exception {
        assertEquals("fail", status(root, "harness"));

        Path agent = root.resolve(".github/agents/gta-analyst.agent.md");
        Files.createDirectories(agent.getParent());
        Files.writeString(agent, "original");
        Files.createDirectories(root.resolve(".gta"));
        Files.writeString(
                root.resolve(".gta/managed-files.json"),
                "{\"platform\":\"copilot\",\"version\":\"0.1.0\",\"files\":{\".github/agents/gta-analyst.agent.md\":\""
                        + com.gta.core.util.Hashes.sha256(agent) + "\"}}");
        assertEquals("ok", status(root, "harness"));
        assertEquals("info", status(root, "ide-auto-approve"));

        Files.writeString(agent, "edited by the team");
        Map<String, String> changed = check(root, "harness");
        assertEquals("warn", changed.get("status"));
        assertTrue(changed.get("detail").contains("gta-analyst"), changed.toString());

        Files.delete(agent);
        assertEquals("warn", status(root, "harness"), "a deleted managed file is reported too");
    }

    @Test
    void shouldRollTheChecksUpIntoTheWorstStatus(@TempDir Path root) {
        Map<String, Object> out = new DoctorChecks(root).run();

        assertEquals("fail", out.get("overall"));
    }

    private static void javaPom(Path root, String version) throws Exception {
        Files.writeString(
                root.resolve("pom.xml"),
                "<project><artifactId>a</artifactId><properties><java.version>" + version
                        + "</java.version></properties></project>");
    }

    @Test
    void shouldAcceptServicesOnJava8To16WithAWarningAndRefuseOlderOnes(@TempDir Path root) throws Exception {
        javaPom(root, "11");
        Map<String, String> java11 = check(root, "service-java");
        assertEquals("warn", java11.get("status"));
        assertTrue(java11.get("detail").contains("written for Java 11"), java11.toString());
        assertTrue(java11.get("hint").contains("build.javaHomeEnv"), "an old build may need its own JDK");

        javaPom(root, "1.8");
        assertEquals("warn", status(root, "service-java"));

        javaPom(root, "1.7");
        assertEquals("fail", status(root, "service-java"));

        javaPom(root, "21");
        assertEquals("ok", status(root, "service-java"));
    }

    @Test
    void shouldCheckTheBuildJdkOnlyWhenOneIsConfigured(@TempDir Path root) throws Exception {
        assertThrows(java.util.NoSuchElementException.class, () -> check(root, "build-jdk"));

        Files.createDirectories(root.resolve(".gta"));
        Files.writeString(root.resolve(".gta/config.json"), "{\"build\":{\"javaHomeEnv\":\"GTA_TEST_NO_SUCH_JDK\"}}");
        assertEquals("fail", status(root, "build-jdk"));
    }

    @Test
    void shouldExplainSpringBoot2Support(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), pomWith("2.7.18", "", ""));

        Map<String, String> boot = check(root, "spring-boot");
        assertEquals("warn", boot.get("status"));
        assertTrue(boot.get("hint").contains("javax"), boot.toString());
    }

    @Test
    void shouldCheckGradleAndReadTheBuildScriptOfAGradleService(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("build.gradle"), """
                plugins { id 'org.springframework.boot' version '3.5.4' }
                dependencies { testImplementation 'com.gta:gta-junit:0.1.0' }
                """);
        // the wrapper doctor looks for is the platform's own
        Files.writeString(
                root.resolve(com.gta.core.util.ProcessRunner.isWindows() ? "gradlew.bat" : "gradlew"), "#!/bin/sh\n");

        assertEquals("ok", status(root, "gradle"));
        assertEquals("Gradle wrapper", check(root, "gradle").get("detail"));
        assertThrows(java.util.NoSuchElementException.class, () -> check(root, "maven"));
        assertThrows(java.util.NoSuchElementException.class, () -> check(root, "failsafe"), "Failsafe is Maven's");
        assertTrue(check(root, "spring-boot").get("detail").contains("3.5.4"));
        assertEquals("ok", status(root, "gta-junit"));
    }
}
