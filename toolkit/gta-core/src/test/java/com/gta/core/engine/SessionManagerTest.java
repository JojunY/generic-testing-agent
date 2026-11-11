package com.gta.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.gta.core.state.WorkState;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Session ownership (plan §5.2). */
class SessionManagerTest {
    @TempDir
    Path root;

    private WorkState session(String lastSeenAt) {
        WorkState st = new WorkState();
        st.session = new WorkState.Session();
        st.session.id = "s20261104090000-old";
        st.session.lastSeenAt = lastSeenAt;
        return st;
    }

    @Test
    void shouldKeepAnActiveSession() {
        WorkState st = session(Instant.now().toString());

        new EngineContext(root).sessions.touchSession(st, false);

        assertEquals("s20261104090000-old", st.session.id);
    }

    @Test
    void shouldStartANewSessionAfterTheIdleTimeout() {
        WorkState st = session(Instant.now().minusSeconds(3600).toString());

        new EngineContext(root).sessions.touchSession(st, false);

        assertNotEquals("s20261104090000-old", st.session.id);
    }

    @Test
    void shouldInvalidateTheTokensOfTheOldSession() {
        WorkState st = session(Instant.now().toString());
        WorkState.ItemState review = new WorkState.ItemState();
        review.id = "order.create/s01#component-01";
        review.status = com.gta.core.state.ItemStatus.REVIEWING;
        review.token = "s20261104090000-old:0007";
        st.items.put(review.id, review);

        new EngineContext(root).sessions.touchSession(st, true);

        assertEquals(com.gta.core.state.ItemStatus.REVIEWING, review.status, "the review waits for the new session");
        assertNull(review.token, "a verdict with the old session's token is refused");
    }

    @Test
    void shouldTreatAnUnreadableTimestampAsIdle() {
        WorkState st = session("yesterday-ish");

        new EngineContext(root).sessions.touchSession(st, false);

        assertNotEquals("s20261104090000-old", st.session.id, "a hand-edited state must not lock the session forever");
    }
}
