package com.gta.core.engine;

import com.gta.core.config.GtaPaths;
import com.gta.core.error.GtaException;
import com.gta.core.plan.PlanItem;
import com.gta.core.state.ItemStatus;
import com.gta.core.state.StateStore;
import com.gta.core.state.WorkState;
import com.gta.core.util.Proc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Engine state-machine paths that need no Maven run. */
class EngineTest {
    static final String ITEM = "order.create/s01#component-01";

    @TempDir
    Path root;
    StateStore store;

    @BeforeEach
    void itemUnderReview() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Proc.run(root, Duration.ofSeconds(30), Map.of(), List.of("git", "init", "-q"));
        GtaPaths paths = new GtaPaths(root);
        store = new StateStore(paths);

        PlanItem item = new PlanItem();
        item.id = ITEM;
        item.kind = "test";
        item.ref = "order.create/s01";
        item.flowId = "order.create";
        item.layer = "component";
        item.testClass = "com.acme.OrderCreateIT";
        item.oracle.source = "observed";
        store.savePlan(List.of(item));

        WorkState st = new WorkState();
        st.session = new WorkState.Session();
        st.session.id = "s20261104090000-test";
        st.session.lastSeenAt = Instant.now().toString();
        WorkState.ItemState s = new WorkState.ItemState();
        s.id = ITEM;
        s.status = ItemStatus.REVIEWING;
        st.items.put(ITEM, s);
        store.save(st);
    }

    @Test
    void shouldSendARejectedOracleBackToTheAuthor() {
        Map<String, Object> out = new Engine(root).submitReview(ITEM, "reject", "asserts the wrong field");

        WorkState.ItemState s = store.load().items.get(ITEM);
        assertEquals(ItemStatus.REPAIRING, s.status, out.toString());
        assertEquals(1, s.attempts);
        assertTrue(s.lastError.message.contains("asserts the wrong field"));
    }

    @Test
    void shouldRejectAnUnknownOracleVerdictWithoutChangingState() {
        assertThrows(GtaException.class, () -> new Engine(root).submitReview(ITEM, "maybe", "unsure"));

        assertEquals(ItemStatus.REVIEWING, store.load().items.get(ITEM).status);
    }
}
