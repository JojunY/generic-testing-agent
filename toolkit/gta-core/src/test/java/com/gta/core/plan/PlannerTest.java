package com.gta.core.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.profile.ServiceProfile;
import com.gta.core.registry.CapabilityRegistry;
import com.gta.core.spec.Spec;
import com.gta.core.spec.SpecParser;
import com.gta.core.spec.SpecTestData;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Planning (plan §4.3) and the test naming rule (docs/guides/test-naming.md). */
class PlannerTest {
    private static ServiceProfile profile() {
        ServiceProfile profile = new ServiceProfile();
        profile.basePackage = "com.acme";
        profile.applicationClass = "com.acme.OrderApplication";
        return profile;
    }

    private static Spec spec() {
        return SpecParser.parseText(SpecTestData.CREATE_ORDER);
    }

    @Test
    void shouldPlanAFoundationAndOneItemPerAnsweredScenario() {
        List<PlanItem> items = Planner.plan(spec(), CapabilityRegistry.loadBundled(), profile());

        assertEquals(
                List.of("order#foundation-01", "order.create/s01#component-01"),
                items.stream().map(i -> i.id).toList(),
                "the assumed scenario s02 must wait for its question");
        PlanItem s01 = items.get(1);
        assertEquals("characterization", s01.intent);
        assertEquals(List.of("201", "50.00"), s01.oracle.then);
        assertEquals(List.of("order#foundation-01"), s01.dependsOn);
    }

    @Test
    void shouldNameTheTestAfterTheSourceClassWithTheLayerSuffix() {
        PlanItem s01 = Planner.plan(spec(), CapabilityRegistry.loadBundled(), profile())
                .get(1);

        assertEquals(
                "com.acme.order.OrderControllerIT",
                s01.testClass,
                "the ref is method:com.acme.order.OrderController#create");
        assertEquals(
                List.of(
                        "src/test/java/com/acme/order/OrderControllerIT.java",
                        "src/test/resources/com/acme/order/OrderControllerIT/**"),
                s01.ownsFiles,
                "fixtures sit in a folder named after the test class");
    }

    @Test
    void shouldPreferTheEntryPointTheProfilerFound() {
        ServiceProfile profile = profile();
        ServiceProfile.EntryPoint e = new ServiceProfile.EntryPoint();
        e.flowId = "order.create";
        e.ref = "method:com.acme.order.api.OrderResource#post";
        profile.entrypoints.add(e);

        PlanItem s01 =
                Planner.plan(spec(), CapabilityRegistry.loadBundled(), profile).get(1);

        assertEquals("com.acme.order.api.OrderResourceIT", s01.testClass);
    }

    @Test
    void shouldNameTheSeedAfterTheApplicationClass() {
        PlanItem seed = Planner.plan(spec(), CapabilityRegistry.loadBundled(), profile())
                .get(0);

        assertEquals("com.acme.OrderApplicationIT", seed.testClass);
        assertTrue(seed.ownsFiles.contains("pom.xml"));
        assertTrue(seed.ownsFiles.contains("build.gradle.kts"), "a Gradle service's foundation edits its build script");
        assertTrue(seed.ownsFiles.contains("src/test/java/com/acme/support/**"));
    }

    @Test
    void shouldFallBackToApplicationItWithoutAnApplicationClass() {
        ServiceProfile profile = profile();
        profile.applicationClass = null;

        assertEquals("com.acme.ApplicationIT", Planner.seedClass(profile));
        profile.basePackage = "";
        assertEquals("ApplicationIT", Planner.seedClass(profile), "the default package has no dot");
    }

    @Test
    void shouldGiveEveryLayerItsOwnSuffix() {
        assertEquals("Test", Planner.SUFFIX.get("unit"));
        assertEquals("SliceTest", Planner.SUFFIX.get("slice"));
        assertEquals("IT", Planner.SUFFIX.get("component"));
        assertEquals("ContractIT", Planner.SUFFIX.get("contract"));
        assertEquals("Karate", Planner.SUFFIX.get("system"));
    }

    @Test
    void shouldNameAFlowWithoutCodeRefsAfterTheFlow() {
        Spec noRefs = SpecParser.parseText(
                SpecTestData.CREATE_ORDER.replace(" ref=method:com.acme.order.OrderController#create", ""));

        PlanItem s01 = Planner.plan(noRefs, CapabilityRegistry.loadBundled(), profile())
                .get(1);

        assertEquals(
                "com.acme.OrderCreateIT",
                s01.testClass,
                "no source to mirror: named after the flow in the base package");
    }

    @Test
    void shouldTakeThePriorityTagFromTheScenario() {
        Spec p0 = SpecParser.parseText(SpecTestData.CREATE_ORDER.replace(
                "id=order.create/s01 source=observed", "id=order.create/s01 source=observed priority=P0"));

        assertEquals(
                "p0",
                Planner.plan(p0, CapabilityRegistry.loadBundled(), profile()).get(1).priority);
        assertEquals(
                "p1",
                Planner.plan(spec(), CapabilityRegistry.loadBundled(), profile())
                        .get(1)
                        .priority,
                "a scenario without priority counts as P1");
        assertEquals(
                "p0",
                Planner.plan(spec(), CapabilityRegistry.loadBundled(), profile())
                        .get(0)
                        .priority,
                "seed");
    }

    @Test
    void shouldOwnTheTestFilesOfTheServiceModuleOnly() {
        ServiceProfile p = profile();
        p.module = "order-api";

        List<PlanItem> items = Planner.plan(spec(), CapabilityRegistry.loadBundled(), p);

        for (PlanItem i : items) {
            assertTrue(i.ownsFiles.stream().allMatch(f -> f.startsWith("order-api/")), i.id + " " + i.ownsFiles);
        }
        assertTrue(items.get(0).ownsFiles.contains("order-api/pom.xml"), "the foundation edits the module's pom");
    }

    @Test
    void shouldPlanAReproducingItemForAScenarioThatAsksForIt() {
        Spec incident = SpecParser.parseText(SpecTestData.CREATE_ORDER.replaceFirst(
                "source=observed hint.layer=component",
                "source=observed hint.intent=reproducing hint.layer=component"));

        PlanItem s01 = Planner.plan(incident, CapabilityRegistry.loadBundled(), profile())
                .get(1);

        assertEquals("reproducing", s01.intent, "hint.intent overrides the intent the source implies");
    }
}
