package com.gta.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ItemStatusTest {

    @Test
    void shouldOnlyLetGtawReachPassedThroughVerification() {
        assertFalse(ItemStatus.AUTHORING.canMoveTo(ItemStatus.PASSED));
        assertTrue(ItemStatus.VERIFYING.canMoveTo(ItemStatus.PASSED));
        assertTrue(ItemStatus.REVIEWING.canMoveTo(ItemStatus.PASSED));
    }

    @Test
    void shouldTreatPassedDefectAndCancelledAsTerminal() {
        assertTrue(ItemStatus.PASSED.terminal());
        assertTrue(ItemStatus.DEFECT_FOUND.terminal());
        assertTrue(ItemStatus.CANCELLED.terminal());
        assertFalse(ItemStatus.NEEDS_HUMAN.terminal());
    }

    @Test
    void shouldRejectAnIllegalTransition() {
        WorkState st = new WorkState();
        WorkState.ItemState s = new WorkState.ItemState();
        s.id = "order.create/s01#component-01";
        st.items.put(s.id, s);

        assertThrows(RuntimeException.class, () -> st.move(s, ItemStatus.PASSED));
    }

    @Test
    void shouldLetARejectedOracleGoBackToTheAuthor() {
        assertTrue(ItemStatus.REVIEWING.canMoveTo(ItemStatus.REPAIRING));
        assertTrue(ItemStatus.REVIEWING.canMoveTo(ItemStatus.NEEDS_HUMAN));
    }

    @Test
    void shouldAllowAdoptingAnExistingTestOnRebuild() {
        assertTrue(ItemStatus.PENDING.canMoveTo(ItemStatus.VERIFYING));
    }

    @Test
    void shouldUseKebabCaseIdsInTheStateFile() {
        for (ItemStatus st : ItemStatus.values()) {
            assertEquals(st, ItemStatus.of(st.id()));
        }
        assertEquals("needs-human", ItemStatus.NEEDS_HUMAN.id());
        assertEquals(ItemStatus.DEFECT_FOUND, ItemStatus.of("defect-found"));
    }

    @Test
    void shouldNeverLeaveCancelledAndOnlyLetHumansDecideOnNeedsHuman() {
        for (ItemStatus to : ItemStatus.values()) {
            assertFalse(ItemStatus.CANCELLED.canMoveTo(to), "cancelled is final: " + to);
        }
        assertTrue(ItemStatus.NEEDS_HUMAN.canMoveTo(ItemStatus.DEFECT_FOUND));
        assertTrue(ItemStatus.NEEDS_HUMAN.canMoveTo(ItemStatus.PENDING));
        assertFalse(ItemStatus.NEEDS_HUMAN.canMoveTo(ItemStatus.PASSED), "a human cannot declare a test passed");
        assertTrue(ItemStatus.PASSED.canMoveTo(ItemStatus.TRIAGING), "stability runs can reopen a passed item");
        assertTrue(ItemStatus.DEFECT_FOUND.canMoveTo(ItemStatus.PASSED), "a fixed defect makes the pin pass");
    }

    @Test
    void shouldMoveAlongALegalTransitionAndIgnoreAMoveToTheSameState() {
        WorkState st = new WorkState();
        WorkState.ItemState s = new WorkState.ItemState();
        s.id = "order.create/s01#component-01";
        st.items.put(s.id, s);

        st.move(s, ItemStatus.PENDING);
        st.move(s, ItemStatus.AUTHORING);

        assertEquals(ItemStatus.AUTHORING, s.status);
        assertEquals(s, st.item(s.id));
        assertThrows(RuntimeException.class, () -> st.item("order.create/s09#component-01"));
    }
}
