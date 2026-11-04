package com.gta.core.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.profile.ServiceProfile;
import com.gta.core.registry.CapabilityRegistry;
import com.gta.core.spec.SpecParser;
import com.gta.core.spec.SpecTestData;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlannerTest {

    @Test
    void shouldPlanAFoundationAndOneItemPerAnsweredScenario() {
        ServiceProfile profile = new ServiceProfile();
        profile.basePackage = "com.acme";

        List<PlanItem> items = Planner.plan(
                SpecParser.parseText(SpecTestData.CREATE_ORDER), CapabilityRegistry.loadBundled(), profile);

        assertEquals(
                List.of("order#foundation-01", "order.create/s01#component-01"),
                items.stream().map(i -> i.id).toList(),
                "the assumed scenario s02 must wait for its question");
        PlanItem seed = items.get(0);
        assertEquals("com.acme.OrderSeedIT", seed.testClass);
        assertTrue(seed.ownsFiles.contains("pom.xml"));
        PlanItem s01 = items.get(1);
        assertEquals("com.acme.order.OrderCreateIT", s01.testClass);
        assertEquals("characterization", s01.intent);
        assertEquals(List.of("201", "50.00"), s01.oracle.then);
        assertEquals(List.of("order#foundation-01"), s01.dependsOn);
    }

    @Test
    void shouldMapRiskToPriority() {
        assertEquals("p0", Planner.priority(9));
        assertEquals("p1", Planner.priority(6));
        assertEquals("p2", Planner.priority(3));
        assertEquals("p3", Planner.priority(1));
    }
}
