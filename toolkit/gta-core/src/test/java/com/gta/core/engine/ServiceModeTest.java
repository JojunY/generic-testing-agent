package com.gta.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.profile.ServiceProfile;
import com.gta.core.state.WorkState;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Service mode: every flow, riskiest first, and decisions parked while other flows go on (plan §6). */
class ServiceModeTest {
    private static ServiceProfile.EntryPoint entry(String flowId, int risk) {
        ServiceProfile.EntryPoint e = new ServiceProfile.EntryPoint();
        e.flowId = flowId;
        e.risk = risk;
        return e;
    }

    private static WorkState.Pending pending(String flowId) {
        WorkState.Pending p = new WorkState.Pending();
        p.type = "answer";
        p.flowId = flowId;
        return p;
    }

    @Test
    void shouldQueueEveryFlowRiskiestFirst() {
        ServiceProfile profile = new ServiceProfile();
        profile.entrypoints.addAll(List.of(entry("order.get", 2), entry("order.create", 9), entry("order.cancel", 6)));
        WorkState st = new WorkState();
        st.parked.add(pending("old.flow"));

        ServiceMode.start(st, profile);

        assertEquals("order.create", st.activeFlow);
        assertEquals(List.of("order.cancel", "order.get"), st.queue);
        assertTrue(st.parked.isEmpty(), "a new service run starts without old decisions");
        WorkState none = new WorkState();
        ServiceMode.start(none, new ServiceProfile());
        assertNull(none.activeFlow, "no flows");
    }

    @Test
    void shouldParkADecisionOnlyInServiceModeWhileAnotherFlowCanGoOn() {
        WorkState st = new WorkState();
        st.mode = ServiceMode.MODE;
        st.activeFlow = "order.create";
        st.pending = pending("order.create");
        st.queue.add("order.cancel");

        assertTrue(ServiceMode.park(st));

        assertNull(st.pending);
        assertEquals("order.cancel", st.activeFlow);
        assertEquals(List.of("order.create"), ServiceMode.waitingFlows(st));
        st.pending = pending("order.cancel");
        assertFalse(ServiceMode.park(st), "the last flow asks now");
        st.queue.add("order.refund");
        st.pending.type = "question";
        assertFalse(ServiceMode.park(st), "an author waits for its answer: questions are asked at once");
        st.queue.clear();
        st.pending.type = "answer";
        st.mode = "flow";
        st.queue.add("order.get");
        assertFalse(ServiceMode.park(st), "flow mode never parks");
    }

    @Test
    void shouldAskTheOldestParkedDecisionFirst() {
        WorkState st = new WorkState();
        st.mode = ServiceMode.MODE;
        st.parked.addAll(List.of(pending("order.create"), pending("order.cancel")));

        assertTrue(ServiceMode.unpark(st));

        assertEquals("order.create", st.pending.flowId);
        assertEquals("order.create", st.activeFlow);
        assertEquals(List.of("order.cancel"), ServiceMode.waitingFlows(st));
        st.mode = "pr";
        assertFalse(ServiceMode.unpark(st));
    }
}
