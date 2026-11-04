package com.gta.core.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
