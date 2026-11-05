package com.gta.core.engine;

import com.gta.core.config.GtaPaths;
import com.gta.core.profile.ServiceProfile;
import com.gta.core.spec.Ref;
import com.gta.core.spec.SpecEditor;
import com.gta.core.spec.SpecParser;
import com.gta.core.state.WorkState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PR mode (plan §6): the diff decides which flows to work on. Approved flows whose cited code, config or files
 * changed are marked {@code stale}, entry points of changed classes without a spec get one, and the flows are worked
 * on one after another in the same session; the PR description is a documented intent for the analyst.
 */
final class PrMode {
    private final EngineContext ctx;
    private final GtaPaths paths;

    PrMode(EngineContext ctx) {
        this.ctx = ctx;
        this.paths = ctx.paths;
    }

    /** The PR's flows, in order: stale approved flows first (by impact), then new entry points of changed classes. */
    Map<String, Object> start(WorkState st, ServiceProfile profile, String base, String intent) {
        Map<String, Object> impact = ctx.cases.impactUnlocked(base);
        @SuppressWarnings("unchecked")
        List<String> needsReview = (List<String>) impact.get("needsReview");
        @SuppressWarnings("unchecked")
        Map<String, String> reasons = (Map<String, String>) impact.get("reasons");
        @SuppressWarnings("unchecked")
        List<String> changedClasses = (List<String>) impact.get("changedClasses");

        Set<String> flows = new LinkedHashSet<>();
        Map<String, String> why = new LinkedHashMap<>();
        for (String scenario : needsReview) {
            String flowId = scenario.substring(0, scenario.indexOf('/'));
            if (flows.add(flowId)) {
                why.put(flowId, reasons.get(scenario));
                Path spec = paths.spec(flowId);
                if ("approved".equals(SpecParser.parse(spec).status())) {
                    ctx.specs.writeSpec(
                            spec,
                            new SpecEditor(SpecParser.parse(spec).text)
                                    .setHeader("status", "stale")
                                    .text());
                }
            }
        }
        List<String> unmapped = new ArrayList<>();
        for (ServiceProfile.EntryPoint e : profile.entrypoints) {
            String cls = Ref.parse(e.ref).map(Ref::target).orElse("");
            if (changedClasses.contains(cls) && !Files.exists(paths.spec(e.flowId)) && flows.add(e.flowId)) {
                why.put(e.flowId, "new or changed entry point " + e.ref + " without a spec");
            }
        }
        for (String cls : changedClasses) {
            boolean mapped = profile.entrypoints.stream()
                    .anyMatch(e -> Ref.parse(e.ref).map(Ref::target).orElse("").equals(cls));
            boolean cited = needsReview.stream()
                    .anyMatch(s -> String.valueOf(reasons.get(s)).contains(cls));
            if (!mapped && !cited) {
                unmapped.add(cls);
            }
        }
        st.prQueue = new ArrayList<>(flows);
        st.prIntent = intent == null || intent.isBlank() ? null : intent.strip();
        st.activeFlow = st.prQueue.isEmpty() ? null : st.prQueue.remove(0);
        st.pending = null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("base", base);
        out.put("flows", List.copyOf(flows));
        out.put("why", why);
        out.put("unmapped", unmapped);
        return out;
    }

    /** The next queued flow once the active one has nothing left to do in this session, or null. */
    static String advance(WorkState st) {
        return "pr".equals(st.mode) && !st.prQueue.isEmpty() ? st.prQueue.remove(0) : null;
    }
}
