package com.gta.core.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * The only way gtaw writes files (state, specs, reports, escalations, installed harness files): write a temp file next to
 * the target, then rename it over the target, so a crash never leaves a half-written file.
 */
public final class AtomicFiles {
    private AtomicFiles() {}

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
        keepPermissions(tmp, target);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * A temp file is owner-only (rw-------): the target keeps its own permissions, and a new file gets rw-r--r--, as
     * other tools create it, so the work tree stays readable for CI users and containers.
     */
    private static void keepPermissions(Path tmp, Path target) throws IOException {
        if (tmp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(
                    tmp,
                    Files.exists(target)
                            ? Files.getPosixFilePermissions(target)
                            : PosixFilePermissions.fromString("rw-r--r--"));
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
