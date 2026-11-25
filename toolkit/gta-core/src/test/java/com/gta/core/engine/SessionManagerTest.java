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
        WorkState.SpecWork analyze = new WorkState.SpecWork();
        analyze.token = "s20261104090000-old:0006";
        st.specWork.put("order.create", analyze);

        new EngineContext(root).sessions.touchSession(st, true);

        assertEquals(com.gta.core.state.ItemStatus.REVIEWING, review.status, "the review waits for the new session");
        assertNull(review.token, "a verdict with the old session's token is refused");
        assertNull(analyze.token, "so is a spec submitted with the old session's token");
    }

    @Test
    void shouldTreatAnUnreadableTimestampAsIdle() {
        WorkState st = session("yesterday-ish");

        new EngineContext(root).sessions.touchSession(st, false);

        assertNotEquals("s20261104090000-old", st.session.id, "a hand-edited state must not lock the session forever");
    }

    @Test
    void shouldRunAReviewedTestAgainWhenTheFilesChangedSinceItsReviewRound() throws Exception {
        OrderServiceTestData.create(root);
        WorkState st = session(Instant.now().minusSeconds(3600).toString());
        WorkState.ItemState review = new WorkState.ItemState();
        review.id = "order.create/s01#component-01";
        review.status = com.gta.core.state.ItemStatus.REVIEWING;
        review.reviewBaseline = new com.gta.core.change.ChangeChecker(new EngineContext(root).paths).capture();
        st.items.put(review.id, review);
        WorkState.ItemState triage = new WorkState.ItemState();
        triage.id = "order.create/s02#component-01";
        triage.status = com.gta.core.state.ItemStatus.TRIAGING;
        st.items.put(triage.id, triage);
        Path edited = root.resolve("src/main/java/Developer.java");
        java.nio.file.Files.createDirectories(edited.getParent());
        java.nio.file.Files.writeString(edited, "class Developer {}\n");

        new EngineContext(root).sessions.touchSession(st, false);

        assertEquals(com.gta.core.state.ItemStatus.VERIFYING, review.status, "the reviewed run is out of date");
        assertNull(review.reviewBaseline);
        assertEquals(com.gta.core.state.ItemStatus.TRIAGING, triage.status, "no round was issued yet: nothing to redo");
        assertEquals("class Developer {}\n", java.nio.file.Files.readString(edited), "the developer's edit is kept");
    }
}
