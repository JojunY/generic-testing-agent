package com.gta.core.engine;

import com.gta.core.profile.ServiceProfile;
import com.gta.core.state.WorkState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Service mode (plan §6): every detected flow of the service, riskiest first, worked on one after another. A flow that
 * waits for a person does not stop the others: its decision is parked and gta goes on with the next flow; the parked
 * decisions are asked once nothing else can go on without a person.
 */
final class ServiceMode {
    static final String MODE = "service";

    private ServiceMode() {}

    /** Queues every flow of the service, riskiest first, and makes the first one active (none: no active flow). */
    static void start(WorkState st, ServiceProfile profile) {
        Set<String> flows = new LinkedHashSet<>();
        profile.entrypoints.stream()
                .sorted(Comparator.comparingInt((ServiceProfile.EntryPoint e) -> -e.risk))
                .forEach(e -> flows.add(e.flowId));
        st.queue = new ArrayList<>(flows);
        st.parked.clear();
        st.pending = null;
        st.activeFlow = st.queue.isEmpty() ? null : st.queue.remove(0);
    }

    /**
     * Parks the decision the active flow waits for and makes the next queued flow active, when there is one; false
     * when the decision must be asked now.
     */
    static boolean park(WorkState st) {
        // an author's question is asked now: the item keeps its scope baseline while it waits, and other flows'
        // changes made in between would read as out of scope when it resumes
        if (!MODE.equals(st.mode) || st.pending == null || "question".equals(st.pending.type) || st.queue.isEmpty()) {
            return false;
        }
        st.parked.add(st.pending);
        st.pending = null;
        st.activeFlow = st.queue.remove(0);
        return true;
    }

    /** The oldest parked decision becomes the pending one, its flow active; false when none is parked. */
    static boolean unpark(WorkState st) {
        if (!MODE.equals(st.mode) || st.parked.isEmpty()) {
            return false;
        }
        st.pending = st.parked.remove(0);
        st.activeFlow = st.pending.flowId;
        return true;
    }

    /** The flows of the parked decisions, for the status block. */
    static List<String> waitingFlows(WorkState st) {
        return MODE.equals(st.mode)
                ? st.parked.stream().map(p -> p.flowId).distinct().toList()
                : List.of();
    }
}
