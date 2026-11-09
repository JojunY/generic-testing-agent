package com.gta.core.plan;

import com.gta.core.config.GtaPaths;
import com.gta.core.profile.ServiceProfile;
import com.gta.core.registry.CapabilityRegistry;
import com.gta.core.spec.Ref;
import com.gta.core.spec.Spec;
import com.gta.core.spec.SpecLinter;
import com.gta.core.util.Ids;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
        Optional<Ref> source = sourceOf(spec, profile);
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

                PlanItem item = new PlanItem();
                item.id = s.id() + "#" + layer + "-01";
                item.kind = "test";
                item.ref = s.id();
                item.flowId = flowId;
                item.title = s.title;
                item.capability = capability.id;
                item.layer = layer;
                item.intent = s.meta.get("hint.intent")
                        .filter(SpecLinter.INTENTS::contains)
                        .orElse(
                                switch (s.source()) {
                                    case "confirmed", "documented" -> "hardening";
                                    default -> "characterization";
                                });
                item.skills = new ArrayList<>(skills);
                item.oracle.type = s.meta.getOr("hint.oracle", "state");
                item.oracle.source = s.source();
                item.oracle.then = s.thenLiterals();
                item.oracle.reviewerChecked = s.reviewerChecked();
                item.testClass = testClass(source, layer, domain, flowName, profile);
                if (profile.cucumber != null && layer.equals("component")) {
                    // the service runs its component scenarios with Cucumber: a feature file and its steps (plan §7.10)
                    item.feature = GtaPaths.inModule(profile.module, featureFile(profile.cucumber, domain, flowName));
                    item.steps = stepsClass(profile.cucumber, item.testClass);
                    item.testClass = profile.cucumber.suite;
                    item.ownsFiles.add(item.feature);
                    item.ownsFiles.add(GtaPaths.testSourceRel(profile.module, item.steps));
                    item.ownsFiles.add(GtaPaths.inModule(profile.module, fixtureFolder(item.steps) + "/**"));
                    item.ownsFiles.add(GtaPaths.testSourceRel(profile.module, cucumberHooks(profile.cucumber)));
                } else {
                    item.ownsFiles.add(GtaPaths.testSourceRel(profile.module, item.testClass));
                    item.ownsFiles.add(GtaPaths.inModule(profile.module, fixtureFolder(item.testClass) + "/**"));
                }
                item.priority = priority(s);
                item.scenarioRevision = s.revision();
                items.add(item);
            }
        }
        if (needsFoundation && !items.isEmpty()) {
            PlanItem f = new PlanItem();
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
            // the seed boots the whole application: it is the test of the @SpringBootApplication class
            f.testClass = seedClass(profile);
            String basePkg = new Ref("bean", f.testClass, null).packageName();
            String basePath = basePkg.isEmpty() ? "" : basePkg.replace('.', '/') + "/";
            f.ownsFiles.add(GtaPaths.testSourceRel(profile.module, f.testClass));
            f.ownsFiles.add(GtaPaths.inModule(profile.module, GtaPaths.TEST_JAVA + "/" + basePath + "support/**"));
            f.ownsFiles.add(GtaPaths.inModule(profile.module, GtaPaths.TEST_RESOURCES + "/**"));
            if (profile.cucumber != null) {
                // the hook that keeps pinned scenarios green in a plain build (gta-junit KnownDefects)
                f.ownsFiles.add(GtaPaths.testSourceRel(profile.module, cucumberHooks(profile.cucumber)));
            }
            f.ownsFiles.add(GtaPaths.inModule(profile.module, "pom.xml"));
            // a Gradle service's test dependencies go into its build script instead
            f.ownsFiles.add(GtaPaths.inModule(profile.module, "build.gradle"));
            f.ownsFiles.add(GtaPaths.inModule(profile.module, "build.gradle.kts"));
            f.priority = "p0";
            f.scenarioRevision = "foundation";
            for (PlanItem i : items) {
                i.dependsOn.add(f.id);
            }
            items.add(0, f);
        }
        return items;
    }

    /** The scenario's priority as its tag (plan §9.5): {@code p0}, {@code p1} or {@code p2}. */
    public static String priority(Spec.Scenario s) {
        return s.priority().toLowerCase(java.util.Locale.ROOT);
    }

    /** Test class suffix of each layer (docs/guides/test-naming.md); one test class per source class and suffix. */
    public static final Map<String, String> SUFFIX = Map.of(
            "unit", "Test", "slice", "SliceTest", "component", "IT", "contract", "ContractIT", "system", "Karate");

    /**
     * The production class a flow's tests are named after: the entry point the profiler found, otherwise the first class
     * the spec refers to.
     */
    static Optional<Ref> sourceOf(Spec spec, ServiceProfile profile) {
        Optional<Ref> entry = profile.entrypoints.stream()
                .filter(e -> spec.flowId().equals(e.flowId))
                .findFirst()
                .flatMap(e -> Ref.parse(e.ref))
                .filter(Ref::isCode);
        if (entry.isPresent()) {
            return entry;
        }
        for (Spec.Requirement r : spec.requirements) {
            for (String ref : r.refs()) {
                Optional<Ref> code = Ref.parse(ref).filter(Ref::isCode);
                if (code.isPresent()) {
                    return code;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * {@code <source class><suffix>} in the source's package ({@code api.OrderController} -> {@code api.OrderControllerIT}).
     * A flow without any code reference has no source to mirror: it is named after the flow, in the base package.
     */
    static String testClass(
            Optional<Ref> source, String layer, String domain, String flowName, ServiceProfile profile) {
        String suffix = SUFFIX.getOrDefault(layer, "Test");
        if (source.isPresent()) {
            return source.get().target() + suffix;
        }
        String base = profile.basePackage == null || profile.basePackage.isEmpty() ? "" : profile.basePackage + ".";
        return base + Ids.pascal(domain) + Ids.pascal(flowName) + suffix;
    }

    /** {@code <@SpringBootApplication class>IT}; {@code ApplicationIT} in the base package when there is none. */
    static String seedClass(ServiceProfile profile) {
        if (profile.applicationClass != null && !profile.applicationClass.isEmpty()) {
            return profile.applicationClass + "IT";
        }
        String base = profile.basePackage == null || profile.basePackage.isEmpty() ? "" : profile.basePackage + ".";
        return base + "ApplicationIT";
    }

    /** {@code src/test/resources/<features>/<domain>/<flow name>.feature}: one feature file per flow. */
    static String featureFile(ServiceProfile.Cucumber cucumber, String domain, String flowName) {
        String folder = cucumber.features == null || cucumber.features.isEmpty() ? "" : cucumber.features + "/";
        return GtaPaths.TEST_RESOURCES + "/" + folder + domain + "/" + flowName + ".feature";
    }

    /**
     * {@code <test class without its suffix>Steps}, in the test's package when the glue covers it, else in the glue
     * package ({@code borrow.BorrowControllerIT} -> {@code borrow.BorrowControllerSteps}).
     */
    static String stepsClass(ServiceProfile.Cucumber cucumber, String testClass) {
        String base = testClass.endsWith("IT") ? testClass.substring(0, testClass.length() - 2) : testClass;
        String pkg = base.contains(".") ? base.substring(0, base.lastIndexOf('.')) : "";
        String simple = base.substring(base.lastIndexOf('.') + 1);
        String glue = cucumber.glue == null ? "" : cucumber.glue;
        boolean covered = glue.isEmpty() || pkg.equals(glue) || pkg.startsWith(glue + ".");
        String target = covered ? pkg : glue;
        return (target.isEmpty() ? "" : target + ".") + simple + "Steps";
    }

    /** The service's hook class for pinned scenarios, in the glue package. */
    public static String cucumberHooks(ServiceProfile.Cucumber cucumber) {
        return (cucumber.glue == null || cucumber.glue.isEmpty() ? "" : cucumber.glue + ".") + "GtaCucumberHooks";
    }

    /** Fixtures of one test class: {@code src/test/resources/<package path>/<TestClass>} (relative to the module). */
    public static String fixtureFolder(String testClass) {
        return GtaPaths.TEST_RESOURCES + "/" + testClass.replace('.', '/');
    }
}
