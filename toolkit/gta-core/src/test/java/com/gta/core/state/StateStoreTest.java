package com.gta.core.state;

import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateStoreTest {
    @TempDir
    Path root;

    @Test
    void shouldKeepACorruptStateFileAndReportIt() throws Exception {
        GtaPaths paths = new GtaPaths(root);
        Files.createDirectories(paths.work());
        Files.writeString(paths.state(), "{ \"items\": {");
        StateStore store = new StateStore(paths);

        GtaException e = assertThrows(GtaException.class, store::load);

        assertEquals(ErrorCode.STATE_CORRUPT, e.code());
        assertFalse(Files.exists(paths.state()), "the next command must be able to rebuild");
        try (Stream<Path> files = Files.list(paths.work())) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("state.json.corrupt-")), "evidence must be kept");
        }
        assertEquals(0, store.load().items.size(), "after reporting, the state is rebuilt from scratch");
    }

    @Test
    void shouldKeepACorruptPlanFileAndReportIt() throws Exception {
        GtaPaths paths = new GtaPaths(root);
        Files.createDirectories(paths.work());
        Files.writeString(paths.plan(), "[ {");

        GtaException e = assertThrows(GtaException.class, () -> new StateStore(paths).loadPlan());

        assertEquals(ErrorCode.STATE_CORRUPT, e.code());
    }
}
