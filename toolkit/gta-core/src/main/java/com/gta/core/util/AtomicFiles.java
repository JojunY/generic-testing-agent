package com.gta.core.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The only way gtaw writes files (state, specs, reports, escalations, installed harness files): write a temp file next to
 * the target, then rename it over the target, so a crash never leaves a half-written file.
 */
public final class AtomicFiles {
    private AtomicFiles() {
    }

    /** Atomic copy of any file (binary-safe), e.g. backups of files gtaw is about to revert. */
    public static void copy(Path source, Path target) {
        try {
            Path parent = parentOf(target);
            Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, "." + target.getFileName(), ".tmp");
            try {
                Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
                move(tmp, target);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The directory a file is written in; a file system root has none and cannot be a target. */
    private static Path parentOf(Path file) {
        Path parent = file.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Not a file path: " + file);
        }
        return parent;
    }

    private static void move(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void write(Path path, String content) {
        try {
            Path parent = parentOf(path);
            Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, "." + path.getFileName(), ".tmp");
            try {
                Files.writeString(tmp, content, StandardCharsets.UTF_8);
                move(tmp, path);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
