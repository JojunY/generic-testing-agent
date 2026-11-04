package com.gta.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The toolkit follows its own naming rule (docs/guides/test-naming.md): every source has a {@code <Name>Test} in the same
 * package, every test maps back to a source, and support code uses a support suffix. Exceptions are listed, with a
 * reason, in toolkit/test-mirror-allowlist.txt.
 */
class MirroredTestNamesTest {
    static final Path TOOLKIT = Path.of("..").toAbsolutePath().normalize();
    static final List<String> SUPPORT_SUFFIXES =
            List.of("Fake.java", "TestData.java", "Stub.java", "TestConfig.java", "Extension.java");

    private static Set<String> allowlist() throws IOException {
        return Files.readAllLines(TOOLKIT.resolve("test-mirror-allowlist.txt")).stream()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .map(l -> l.substring(0, l.indexOf('|')).strip())
                .collect(Collectors.toSet());
    }

    /** Paths under src/main/java (or src/test/java) of every module, relative to that root. */
    private static Set<String> files(String root) throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> modules = Files.list(TOOLKIT)) {
            for (Path module :
                    modules.filter(m -> Files.isDirectory(m.resolve(root))).toList()) {
                try (Stream<Path> files = Files.walk(module.resolve(root))) {
                    files.filter(f -> f.toString().endsWith(".java"))
                            .forEach(f -> out.add(module.resolve(root)
                                    .relativize(f)
                                    .toString()
                                    .replace('\\', '/')));
                }
            }
        }
        return out;
    }

    @Test
    void shouldGiveEverySourceItsOwnTestInTheSamePackage() throws IOException {
        Set<String> tests = files("src/test/java");
        Set<String> allowed = allowlist();
        List<String> missing = new ArrayList<>();
        for (String source : files("src/main/java")) {
            if (!tests.contains(source.replace(".java", "Test.java")) && !allowed.contains(source)) {
                missing.add(source);
            }
        }

        assertEquals(
                List.of(),
                missing,
                "add <Name>Test next to these, or list them with a reason in test-mirror-allowlist.txt");
    }

    @Test
    void shouldMapEveryTestBackToItsSource() throws IOException {
        Set<String> sources = files("src/main/java");
        Set<String> allowed = allowlist();
        List<String> orphans = new ArrayList<>();
        for (String test : files("src/test/java")) {
            boolean support = SUPPORT_SUFFIXES.stream().anyMatch(test::endsWith);
            boolean mirrors = test.endsWith("Test.java") && sources.contains(test.replace("Test.java", ".java"));
            if (!support && !mirrors && !allowed.contains(test)) {
                orphans.add(test);
            }
        }

        assertEquals(
                List.of(),
                orphans,
                "rename them after their source class, or use a support suffix (Fake, TestData, ...)");
    }

    @Test
    void shouldKeepTheAllowlistFreeOfStaleEntries() throws IOException {
        Set<String> all = new TreeSet<>(files("src/main/java"));
        all.addAll(files("src/test/java"));
        List<String> stale =
                allowlist().stream().filter(a -> !all.contains(a)).sorted().toList();

        assertEquals(List.of(), stale, "remove allowlist entries for files that no longer exist");
    }
}
