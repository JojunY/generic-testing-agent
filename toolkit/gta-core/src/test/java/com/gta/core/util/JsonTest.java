package com.gta.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonTest {
    @TempDir
    Path dir;

    @Test
    void shouldWriteReadablePrettyJsonAtomically() throws Exception {
        Path f = dir.resolve("work/state.json");

        Json.writeAtomic(f, Map.of("a", 1));

        assertTrue(Files.readString(f).endsWith("\n"));
        assertEquals(1, Json.read(f, Map.class).get("a"));
    }

    @Test
    void shouldReportAnUnreadableFileAsAConfigError() throws Exception {
        Path f = dir.resolve("bad.json");
        Files.writeString(f, "{not json");

        GtaException e = assertThrows(GtaException.class, () -> Json.read(f, Map.class));

        assertEquals(ErrorCode.CONFIG_INVALID, e.code());
    }
}
