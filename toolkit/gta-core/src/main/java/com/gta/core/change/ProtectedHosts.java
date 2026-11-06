package com.gta.core.change;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * gta tests run locally only (plan §13): the files an item owns must never name a protected host (SIT, UAT,
 * production), so no test gta writes can call or configure one. A deterministic check at submit, before any run.
 */
public final class ProtectedHosts {
    private ProtectedHosts() {}

    /**
     * The owned files under {@code testRoot} that name a protected host, as {@code "<file>: <host>"}.
     *
     * @param root      the repository root; owned-file patterns are relative to it
     * @param testRoot  the directory to look in (the module's {@code src/test})
     */
    public static List<String> named(Path root, Path testRoot, List<String> ownedPatterns, List<String> hosts) {
        List<String> out = new ArrayList<>();
        if (hosts.isEmpty() || !Files.isDirectory(testRoot)) {
            return out;
        }
        List<PathMatcher> owned = ownedPatterns.stream()
                .map(p -> FileSystems.getDefault().getPathMatcher("glob:" + p))
                .toList();
        try (Stream<Path> files = Files.walk(testRoot)) {
            for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                Path rel = root.relativize(f);
                String unix = rel.toString().replace('\\', '/');
                if (owned.stream().noneMatch(m -> m.matches(Path.of(unix)))) {
                    continue;
                }
                String text;
                try {
                    text = Files.readString(f, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                } catch (java.nio.charset.MalformedInputException binary) {
                    continue; // binary fixtures cannot carry a URL the test uses as text
                }
                for (String host : hosts) {
                    if (text.contains(host.toLowerCase(Locale.ROOT))) {
                        out.add(unix + ": " + host);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
