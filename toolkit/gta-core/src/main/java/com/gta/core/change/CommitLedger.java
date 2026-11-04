package com.gta.core.change;

import com.gta.core.config.GtaPaths;
import com.gta.core.util.AtomicFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The commits gtaw made itself, by SHA. The scope check trusts only these: a {@code Gta-*} trailer in a commit message is
 * documentation for humans and can be typed by anyone, so it never marks a commit as gtaw's.
 */
public final class CommitLedger {
    private final GtaPaths paths;

    public CommitLedger(GtaPaths paths) {
        this.paths = paths;
    }

    public void record(String sha) {
        Set<String> all = read();
        all.add(sha);
        AtomicFiles.write(paths.commitLedger(), String.join("\n", all) + "\n");
    }

    public boolean contains(String sha) {
        return read().contains(sha);
    }

    private Set<String> read() {
        Set<String> out = new LinkedHashSet<>();
        if (!Files.exists(paths.commitLedger())) {
            return out;
        }
        try {
            Files.readAllLines(paths.commitLedger(), StandardCharsets.UTF_8).stream().map(String::strip).filter(l -> !l.isEmpty())
                    .forEach(out::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
