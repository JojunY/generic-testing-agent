package com.gta.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

/**
 * Pinned Cucumber scenarios (plan §5.3 item 7): aborted in a plain build, checked against the recorded fingerprint in
 * a gtaw run, and written by the listener with their scenario tag and feature file.
 */
class KnownDefectsTest {
    /** What assertEquals(10, 0) throws, as the pinned scenarios below fail. */
    static final String RECORDED = Fingerprint.of(AssertionFailedError.class.getName(), "expected: <10> but was: <0>");

    @Test
    void shouldReadTheFingerprintFromTheTagsWithOrWithoutTheAt() {
        assertEquals("abc123", KnownDefects.fingerprint(Arrays.asList("@scenario:x/s01", "@known-defect:abc123")));
        assertEquals("abc123", KnownDefects.fingerprint(Arrays.asList("known-defect", "known-defect:abc123")));
        assertNull(KnownDefects.fingerprint(Arrays.asList("known-defect", "known-defect:")));
    }

    @Test
    void shouldAbortAPinnedScenarioOnlyOutsideAGtaRun() {
        List<String> tags = Arrays.asList("@scenario:borrow.book/s02", "@known-defect", "@known-defect:abc123");
        TestAbortedException aborted =
                assertThrows(TestAbortedException.class, () -> KnownDefects.abortPinnedOutsideGta(tags));
        assertTrue(aborted.getMessage().startsWith(KnownDefectExtension.ABORT_PREFIX + "borrow.book/s02"));
        KnownDefects.abortPinnedOutsideGta(Arrays.asList("@scenario:borrow.book/s01")); // not pinned: runs

        withProperty(KnownDefects.GTA_RUN, "true", () -> KnownDefects.abortPinnedOutsideGta(tags));
    }

    @Test
    void shouldReportPinnedScenariosByTheirRecordedFailureInAGtaRun(@TempDir Path dir) throws Exception {
        Map<String, String> lines = run(dir, true);

        assertTrue(lines.get("s01").contains("\"outcome\":\"passed\""), lines.get("s01"));
        assertTrue(lines.get("s01").contains("\"feature\":"), "the scenario's feature file is recorded");
        assertTrue(lines.get("s01").contains("\"tags\":[\"pins\",\"scenario:pins/s01\"]"), lines.get("s01"));
        assertTrue(lines.get("s02").contains("\"outcome\":\"known-defect\""), lines.get("s02"));
        assertTrue(lines.get("s02").contains("\"pinned\":true"));
        assertTrue(lines.get("s03").contains("\"outcome\":\"failed\""), "a pin that passes is a fixed defect");
        assertTrue(lines.get("s03").contains("no longer reproduces"));
        assertTrue(lines.get("s04").contains("\"outcome\":\"failed\""), "another failure is not the known defect");
        assertTrue(lines.get("s04").contains("fails differently"));
    }

    @Test
    void shouldReportAPinnedScenarioAsAKnownDefectWithoutRunningItInAPlainBuild(@TempDir Path dir) throws Exception {
        Map<String, String> lines = run(dir, false);

        assertTrue(lines.get("s02").contains("\"outcome\":\"known-defect\""), lines.get("s02"));
        assertTrue(lines.get("s03").contains("\"outcome\":\"known-defect\""), "not run, so a fix shows only in gtaw");
    }

    private static Map<String, String> run(Path dir, boolean gtaRun) throws Exception {
        Path feature = dir.resolve("pins.feature");
        Files.write(
                feature,
                Arrays.asList(
                        "@pins",
                        "Feature: pins",
                        "  @scenario:pins/s01",
                        "  Scenario: [pins/s01] a regular scenario",
                        "    Given the value is 1",
                        "    Then the value equals 1",
                        "  @scenario:pins/s02 @known-defect @known-defect:" + RECORDED,
                        "  Scenario: [pins/s02] still fails as recorded",
                        "    Given the value is 0",
                        "    Then the value equals 10",
                        "  @scenario:pins/s03 @known-defect @known-defect:" + RECORDED,
                        "  Scenario: [pins/s03] fixed",
                        "    Given the value is 10",
                        "    Then the value equals 10",
                        "  @scenario:pins/s04 @known-defect @known-defect:" + RECORDED,
                        "  Scenario: [pins/s04] fails differently",
                        "    Given the value is 0",
                        "    Then the value equals 11",
                        "  Scenario: untagged scenarios are not recorded",
                        "    Given the value is 1"));
        Path results = dir.resolve("results");
        withProperty(
                "gta.results.dir",
                results.toString(),
                () -> withProperty(
                        KnownDefects.GTA_RUN,
                        String.valueOf(gtaRun),
                        () -> LauncherFactory.create()
                                .execute(LauncherDiscoveryRequestBuilder.request()
                                        .selectors(selectFile(feature.toFile()))
                                        .configurationParameter("cucumber.glue", "com.gta.junit.cucumber")
                                        .build())));
        Map<String, String> byScenario = new HashMap<>();
        try (Stream<Path> files = Files.list(results)) {
            for (String line : files.flatMap(KnownDefectsTest::lines).collect(Collectors.toList())) {
                int at = line.indexOf("scenario:pins/");
                byScenario.put(
                        line.substring(at + "scenario:pins/".length(), at + "scenario:pins/".length() + 3), line);
            }
        }
        assertEquals(4, byScenario.size(), byScenario.toString());
        return byScenario;
    }

    private static Stream<String> lines(Path f) {
        try {
            return Files.readAllLines(f).stream();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void withProperty(String key, String value, Runnable body) {
        String before = System.getProperty(key);
        System.setProperty(key, value);
        try {
            body.run();
        } finally {
            if (before == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, before);
            }
        }
    }
}
