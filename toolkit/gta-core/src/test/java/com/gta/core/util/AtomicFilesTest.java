package com.gta.core.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every gtaw write goes through here: write next to the target, then rename (AGENTS.md). */
class AtomicFilesTest {
    @TempDir
    Path dir;

    @Test
    void shouldWriteAndReplaceWithoutLeavingTemporaryFiles() throws Exception {
        Path f = dir.resolve("a/b/state.json");

        AtomicFiles.write(f, "one");
        AtomicFiles.write(f, "two");

        assertEquals("two", Files.readString(f));
        try (var files = Files.list(f.getParent())) {
            assertEquals(
                    List.of("state.json"),
                    files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void shouldCopyBinaryFilesExactly() throws Exception {
        Path src = dir.resolve("src.bin");
        byte[] bytes = {0, 1, (byte) 0xff, 10, 13};
        Files.write(src, bytes);

        AtomicFiles.copy(src, dir.resolve("backup/src.bin"));

        assertArrayEquals(bytes, Files.readAllBytes(dir.resolve("backup/src.bin")));
    }

    @Test
    void shouldRefuseAFileSystemRootAsTarget() {
        Path root = dir.getRoot();

        assertThrows(IllegalArgumentException.class, () -> AtomicFiles.write(root, "x"));
    }
}
