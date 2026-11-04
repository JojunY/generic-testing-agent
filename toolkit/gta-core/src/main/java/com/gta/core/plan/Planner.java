package com.gta.core.plan;

import com.gta.core.config.GtaPaths;
import com.gta.core.profile.ServiceProfile;
import com.gta.core.registry.CapabilityRegistry;
import com.gta.core.spec.Ref;
import com.gta.core.spec.Spec;
import com.gta.core.util.Hashes;
import com.gta.core.util.Ids;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * Turns an approved spec into plan items (plan §4.3: no planner agent; this mapping is a workflow).
 * scenario hints + registry + naming rules (plan §9.3) -> item, layer, test class and owned files.
 */
public final class Planner {
    private Planner() {}

    public static List<PlanItem> plan(Spec spec, CapabilityRegistry registry, ServiceProfile profile) {
        String flowId = spec.flowId();
        CapabilityRegistry.Capability capability = registry.capability(spec.capability());
        String domain = Ids.domainOf(flowId);
        String flowName = Ids.flowNameOf(flowId);
        String pkg = packageFor(spec, profile);
        List<String> skills = registry.skillsFor(capability.id, new HashSet<>(profile.capabilities)).stream()
                .map(s -> ".gta/skills/" + s + "/SKILL.md")
                .toList();

        List<PlanItem> items = new ArrayList<>();
        boolean needsFoundation = false;
        for (Spec.Requirement r : spec.requirements) {
            for (Spec.Scenario s : r.scenarios) {
                if ("assumed".equals(s.source()) || s.id() == null) {
                    continue; // assumptions must be answered first (plan §3.2)
                }
                String layer = s.meta.get("hint.layer").orElse(capability.defaultLayer());
                if (!capability.layers.contains(layer) || "system".equals(layer)) {
                    layer = capability.defaultLayer(); // system layer arrives in P4
                }
                boolean integration = layer.equals("component") || layer.equals("contract");
                needsFoundation |= integration;
                String className = Ids.pascal(domain) + Ids.pascal(flowName) + (integration ? "IT" : "Test");

                PlanItem item = new PlanItem();
                item.id = s.id() + "#" + layer + "-01";
                item.kind = "test";
                item.ref = s.id();
                item.flowId = flowId;
                item.title = s.title;
                item.capability = capability.id;
                item.layer = layer;
                item.intent = switch (s.source()) {
                    case "confirmed", "documented" -> "hardening";
                    default -> "characterization";
                };
                item.skills = new ArrayList<>(skills);
                item.oracle.type = s.meta.getOr("hint.oracle", "state");
                item.oracle.source = s.source();
                item.oracle.then = s.thenLiterals();
                item.oracle.reviewerChecked = s.reviewerChecked();
                item.testClass = pkg.isEmpty() ? className : pkg + "." + className;
                item.ownsFiles.add(GtaPaths.testSourceRel(item.testClass));
                item.ownsFiles.add(GtaPaths.TEST_RESOURCES + "/fixtures/" + domain + "/" + flowName + "/**");
                item.priority = priority(spec.risk());
                item.scenarioRevision = scenarioRevision(s);
                items.add(item);
            }
        }
        if (needsFoundation && !items.isEmpty()) {
            PlanItem f = new PlanItem();
            String seed = Ids.pascal(domain) + "SeedIT";
            f.id = domain + "#foundation-01";
            f.kind = "foundation";
            f.ref = domain;
            f.flowId = flowId;
            f.title = "Seed test: application context and embedded dependencies start";
            f.capability = capability.id;
            f.layer = "component";
            f.intent = "characterization";
            f.skills = new ArrayList<>(skills);
            f.oracle.type = "state";
            f.oracle.source = "observed";
            f.oracle.reviewerChecked = true;
            // the seed boots the whole application, so it lives next to the @SpringBootApplication class
            String basePkg = profile.basePackage == null || profile.basePackage.isEmpty() ? pkg : profile.basePackage;
            String basePath = basePkg.isEmpty() ? "" : basePkg.replace('.', '/') + "/";
            f.testClass = basePkg.isEmpty() ? seed : basePkg + "." + seed;
            f.ownsFiles.add(GtaPaths.testSourceRel(f.testClass));
            f.ownsFiles.add(GtaPaths.TEST_JAVA + "/" + basePath + "support/**");
            f.ownsFiles.add(GtaPaths.TEST_RESOURCES + "/**");
            f.ownsFiles.add("pom.xml");
            f.priority = "p0";
            f.scenarioRevision = "foundation";
            for (PlanItem i : items) {
                i.dependsOn.add(f.id);
            }
            items.add(0, f);
        }
        return items;
    }

    public static String priority(int risk) {
        if (risk >= 9) {
            return "p0";
        }
        if (risk >= 6) {
            return "p1";
        }
        if (risk >= 3) {
            return "p2";
        }
        return "p3";
    }

    public static String scenarioRevision(Spec.Scenario s) {
        return Hashes.normalizedShort(
                s.title + "|" + String.join("|", s.whens) + "|" + String.join("|", s.thens) + "|" + s.source());
    }

    /** Package of the first referenced production class, falling back to the service base package. */
    static String packageFor(Spec spec, ServiceProfile profile) {
        for (Spec.Requirement r : spec.requirements) {
            for (String ref : r.refs()) {
                Optional<Ref> code = Ref.parse(ref).filter(Ref::isCode).filter(x -> !x.packageName()
                        .isEmpty());
                if (code.isPresent()) {
                    return code.get().packageName();
                }
            }
        }
        return profile.basePackage == null ? "" : profile.basePackage;
    }
}
