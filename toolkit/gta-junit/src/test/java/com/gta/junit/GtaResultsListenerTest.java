package com.gta.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/** One JSON line per test with its tags and outcome, so gtaw maps results to scenarios (plan §5.3). */
class GtaResultsListenerTest {
    static class Sample {
        @Test
        @Tag("scenario:order.create/s01")
        @DisplayName("[order.create/s01] valid \"order\"")
        void passes() {}

        @Test
        void fails() {
            fail("expected 201");
        }

        @Test
        @Disabled("not yet")
        void skipped() {}

        @Test
        @KnownDefect(id = "order.create/s03", failure = KnownDefectExtensionTest.FINGERPRINT)
        void pinned() {
            fail("discount expected 10.00 but was 0.00");
        }
    }

    @Test
    void shouldWriteOneLinePerTestWithTagsAndOutcome(@TempDir Path dir) throws Exception {
        String before = System.getProperty("gta.results.dir");
        System.setProperty("gta.results.dir", dir.toString());
        try {
            // registered through the ServiceLoader, as in a service that only adds gta-junit as a dependency
            LauncherFactory.create()
                    .execute(LauncherDiscoveryRequestBuilder.request()
                            .selectors(selectClass(Sample.class))
                            .build());
        } finally {
            if (before == null) {
                System.clearProperty("gta.results.dir");
            } else {
                System.setProperty("gta.results.dir", before);
            }
        }

        List<String> lines;
        try (Stream<Path> files = Files.list(dir)) {
            Path f = files.findFirst().orElseThrow();
            lines = Files.readAllLines(f);
        }
        assertEquals(4, lines.size(), String.join("\n", lines));
        String passes = lines.stream()
                .filter(l -> l.contains("\"method\":\"passes\""))
                .findFirst()
                .orElseThrow();
        assertTrue(
                passes.contains("\"tags\":[\"scenario:order.create/s01\"]")
                        && passes.contains("\"outcome\":\"passed\""),
                passes);
        assertTrue(passes.contains("valid \\\"order\\\""), "quotes are escaped: " + passes);
        assertTrue(lines.stream()
                .anyMatch(l -> l.contains("\"method\":\"fails\"") && l.contains("\"outcome\":\"failed\"")));
        assertTrue(lines.stream()
                .anyMatch(l -> l.contains("\"method\":\"skipped\"") && l.contains("\"outcome\":\"skipped\"")));
        assertTrue(lines.stream()
                .anyMatch(l -> l.contains("\"method\":\"pinned\"") && l.contains("\"outcome\":\"known-defect\"")));
        assertTrue(
                lines.stream()
                        .anyMatch(
                                l -> l.contains("\"method\":\"pinned\"") && l.contains("\"tags\":[\"known-defect\"]")),
                "@KnownDefect carries the known-defect tag, so -Dgroups=known-defect selects the pins");
    }

    @Test
    void shouldQuoteJsonSafely() {
        assertEquals("null", GtaResultsListener.quote(null));
        assertEquals("\"a\\\"b\\\\c\\nd\"", GtaResultsListener.quote("a\"b\\c\nd"));
    }

    @org.junit.jupiter.api.Test
    void shouldStayLoadableByServicesOnJava8() throws Exception {
        for (Class<?> type : new Class<?>[] {GtaResultsListener.class, KnownDefectExtension.class, Fingerprint.class}) {
            try (java.io.DataInputStream in =
                    new java.io.DataInputStream(type.getResourceAsStream(type.getSimpleName() + ".class"))) {
                in.readInt(); // magic
                in.readUnsignedShort(); // minor
                org.junit.jupiter.api.Assertions.assertEquals(
                        52, in.readUnsignedShort(), type + " must be Java 8 bytecode (class version 52)");
            }
        }
    }
}
