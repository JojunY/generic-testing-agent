package com.gta.core.engine;

import com.gta.core.change.ChangeChecker;
import com.gta.core.config.GtaConfig;
import com.gta.core.config.GtaPaths;
import com.gta.core.error.GtaException;
import com.gta.core.plan.PlanItem;
import com.gta.core.state.ItemStatus;
import com.gta.core.state.StateStore;
import com.gta.core.state.WorkState;
import com.gta.core.util.Ids;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Session ownership (plan §5.2): start, idle takeover and recovery of interrupted nodes. */
final class SessionManager {
    private final GtaPaths paths;
    private final GtaConfig config;
    private final StateStore store;

    SessionManager(EngineContext ctx) {
        this.paths = ctx.paths;
        this.config = ctx.config;
        this.store = ctx.store;
    }

    /**
     * Starts a session when there is none, when the previous one ended cleanly, on an explicit takeover, or when the
     * previous one went idle. Only items an agent was editing (AUTHORING, REPAIRING) are reset, and only when the previous
     * session was interrupted (takeover or idle): items waiting for triage or review keep their state.
     */
    Map<String, Object> touchSession(WorkState st, boolean takeover) {
        Map<String, Object> recovered = new LinkedHashMap<>();
        Instant now = Instant.now();
        boolean idle = st.session != null && st.session.lastSeenAt != null && !st.sessionEnded
                && Duration.between(Instant.parse(st.session.lastSeenAt), now).toMinutes() >= config.budgets.sessionIdleMinutes;
        boolean interrupted = st.session != null && (takeover || idle);
        if (st.session == null || st.sessionEnded || interrupted) {
            if (interrupted) {
                for (WorkState.ItemState s : st.items.values()) {
                    if (s.status == ItemStatus.AUTHORING || s.status == ItemStatus.REPAIRING) {
                        Path backup = paths.recover().resolve(Ids.itemKey(s.id) + "-" + now.getEpochSecond());
                        try {
                            PlanItem item = store.planItem(s.id);
                            ChangeChecker cc = new ChangeChecker(paths);
                            if (s.baseline != null) {
                                cc.changedSince(s.baseline).stream().filter(f -> ChangeChecker.matchesAny(f, item.ownsFiles))
                                        .forEach(f -> cc.backup(f, backup));
                            }
                        } catch (GtaException ignored) {
                            // item not in plan any more
                        }
                        st.move(s, ItemStatus.PENDING);
                        recovered.put(s.id, "reset to pending; files backed up to " + paths.rel(backup));
                    }
                }
            }
            WorkState.Session session = new WorkState.Session();
            session.id = Ids.newSessionId();
            session.startedAt = now.toString();
            st.session = session;
            st.itemsThisSession = 0;
            st.sessionPassed = new ArrayList<>();
            st.sessionEnded = false;
            st.consecutiveFailures = interrupted ? st.consecutiveFailures : 0;
        }
        st.session.lastSeenAt = now.toString();
        return recovered;
    }
}
