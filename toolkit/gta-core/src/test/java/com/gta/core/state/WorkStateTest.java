package com.gta.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import org.junit.jupiter.api.Test;

/** Item states change only through move(), which enforces the transition table (AGENTS.md, plan §5.4). */
class WorkStateTest {
    @Test
    void shouldMoveOnlyAlongTheTransitionTable() {
        WorkState st = new WorkState();
        WorkState.ItemState s = new WorkState.ItemState();
        s.id = "order.create/s01#component-01";
        st.items.put(s.id, s);

        st.move(s, ItemStatus.AUTHORING);
        GtaException e = assertThrows(GtaException.class, () -> st.move(s, ItemStatus.PASSED));

        assertEquals(ErrorCode.STATE_ILLEGAL_TRANSITION, e.code());
        assertEquals(ItemStatus.AUTHORING, s.status);
        assertEquals(
                ErrorCode.USAGE_UNKNOWN_ITEM,
                assertThrows(GtaException.class, () -> st.item("x#unit-01")).code());
    }
}
