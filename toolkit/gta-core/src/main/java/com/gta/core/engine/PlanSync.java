package com.gta.core.engine;

import static com.gta.core.engine.NodeResults.result;

import com.gta.core.change.ChangeChecker;
import com.gta.core.config.GtaPaths;
import com.gta.core.oracle.AssertionScanner;
import com.gta.core.oracle.Lock;
import com.gta.core.oracle.OracleShard;
import com.gta.core.plan.PlanItem;
import com.gta.core.plan.Planner;
import com.gta.core.profile.ServiceProfile;
import com.gta.core.registry.CapabilityRegistry;
import com.gta.core.spec.Spec;
import com.gta.core.state.ItemStatus;
import com.gta.core.state.StateStore;
import com.gta.core.state.WorkState;
import com.gta.core.util.Git;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps the plan in step with the approved spec and adopts tests that already exist (plan §5.2). */
final class PlanSync {
    private final EngineContext ctx;
    private final GtaPaths paths;
    private final CapabilityRegistry registry;
    private final StateStore store;
    private final Git git;

    PlanSync(EngineContext ctx) {
        this.ctx = ctx;
        this.paths = ctx.paths;
        this.registry = ctx.registry;
        this.store = ctx.store;
        this.git = ctx.git;
    }

    private static void dropPendingDecision(WorkState st, String itemId) {
        if (st.pending != null && itemId.equals(st.pending.itemId)) {
            st.pending = null;
        }
    }

    List<PlanItem> syncPlan(WorkState st, Spec spec, ServiceProfile profile) {
        List<PlanItem> fresh = Planner.plan(spec, registry, profile);
        List<PlanItem> plan = store.loadPlan();
        Map<String, PlanItem> existing = new LinkedHashMap<>();
        plan.stream().filter(i -> i.flowId.equals(spec.flowId())).forEach(i -> existing.put(i.id, i));
        List<PlanItem> result = new ArrayList<>();
        for (PlanItem f : fresh) {
            PlanItem old = existing.remove(f.id);
            WorkState.ItemState s = st.items.get(f.id);
            if (old != null && s != null && old.scenarioRevision.equals(f.scenarioRevision)) {
                result.add(old);
                continue;
            }
            dropPendingDecision(st, f.id); // a human decision about the old item no longer applies
            WorkState.ItemState ns = new WorkState.ItemState();
            ns.id = f.id;
            st.items.put(f.id, ns);
            result.add(f);
        }
        for (PlanItem gone : existing.values()) {
            WorkState.ItemState s = st.items.get(gone.id);
            if (s != null && !s.status.terminal()) {
                st.move(s, ItemStatus.CANCELLED);
            }
            dropPendingDecision(st, gone.id);
        }
        plan.removeIf(i -> i.flowId.equals(spec.flowId()));
        plan.addAll(result);
        store.savePlan(plan);
        // rebuild: tests that already exist for a fresh pending item are verified first
        Map<String, OracleShard.Entry> shard = OracleShard.read(paths.oracleShard(spec.flowId()));
        for (PlanItem i : result) {
            WorkState.ItemState s = st.items.get(i.id);
            Path test = paths.testSource(i.testClass);
            if (i.isFoundation()
                    && s.status == ItemStatus.PENDING
                    && s.attempts == 0
                    && Files.exists(test)
                    && git.isTracked(paths.rel(test))) {
                // the committed seed test is adopted after one verification run instead of being written again
                s.baseline = new ChangeChecker(paths).capture();
                st.move(s, ItemStatus.VERIFYING); // adopt: verify the existing test before trusting it
                continue;
            }
            if (s.status == ItemStatus.PENDING
                    && s.attempts == 0
                    && Files.exists(test)
                    && shard.containsKey(i.id)
                    && i.scenarioRevision.equals(shard.get(i.id).scenarioRevision)) {
                Lock lock = new Lock();
                lock.tokens = new ArrayList<>(shard.get(i.id).tokens);
                AssertionScanner.ScenarioOracle o = ctx.runs.scanOracle(i);
                lock.statements = o.assertionStatements().stream()
                        .filter(x -> lock.tokens.stream().anyMatch(x::contains))
                        .toList();
                lock.watched = o.watchedHash();
                lock.fingerprint = shard.get(i.id).fingerprint;
                s.lock = lock;
                s.baseline = new ChangeChecker(paths).capture();
                st.move(s, ItemStatus.VERIFYING); // adopt: verify the existing test before trusting it
            }
        }
        return result;
    }
}
