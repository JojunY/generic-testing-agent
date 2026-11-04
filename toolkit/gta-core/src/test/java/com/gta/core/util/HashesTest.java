package com.gta.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HashesTest {
    @Test
    void shouldHashTextBytesAndFilesTheSameWay(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("a.txt");
        Files.writeString(f, "abc");

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashes.sha256("abc"));
        assertEquals(Hashes.sha256("abc"), Hashes.sha256("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Hashes.sha256("abc"), Hashes.sha256(f));
        assertEquals(12, Hashes.shortSha("abc").length());
    }

    @Test
    void shouldIgnoreOnlyWhitespaceInANormalizedHash() {
        assertEquals(Hashes.normalizedShort("valid  order\n"), Hashes.normalizedShort("valid order"));
        assertNotEquals(
                Hashes.normalizedShort("Valid order"), Hashes.normalizedShort("valid order"), "case is content");
        assertNotEquals(Hashes.normalizedShort("valid order"), Hashes.normalizedShort("invalid order"));
    }
}
