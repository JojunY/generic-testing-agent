package com.gta.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
            assertTrue(
                    files.anyMatch(p -> p.getFileName().toString().startsWith("state.json.corrupt-")),
                    "evidence must be kept");
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

    @Test
    void shouldCountTheSessionAsActiveUntilTheCommandSaves() throws Exception {
        GtaPaths paths = new GtaPaths(root);
        StateStore store = new StateStore(paths);
        WorkState st = new WorkState();
        st.session = new WorkState.Session();
        st.session.lastSeenAt = "2026-01-01T00:00:00Z";

        store.save(st);

        assertTrue(Instant.parse(store.load().session.lastSeenAt).isAfter(Instant.parse("2026-11-01T00:00:00Z")));
    }

    @Test
    void shouldReportButNotMoveACorruptFileWhenOnlyReading() throws Exception {
        GtaPaths paths = new GtaPaths(root);
        Files.createDirectories(paths.work());
        Files.writeString(paths.state(), "{ broken");

        assertThrows(GtaException.class, () -> new StateStore(paths).peek());

        assertTrue(Files.exists(paths.state()), "status and report must not move files aside");
    }

    @Test
    void shouldReadTheSavedPlanWithoutTakingTheLock() throws Exception {
        StateStore store = new StateStore(new GtaPaths(root));
        assertEquals(0, store.peekPlan().size(), "no plan yet");

        com.gta.core.plan.PlanItem item = new com.gta.core.plan.PlanItem();
        item.id = "order.create/s01#component-01";
        store.savePlan(java.util.List.of(item));

        assertEquals("order.create/s01#component-01", store.peekPlan().get(0).id);
    }
}
