package com.gta.core.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.error.GtaException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The bundled capability registry (core/registry/capabilities.yaml, plan §6). */
class CapabilityRegistryTest {
    final CapabilityRegistry registry = CapabilityRegistry.loadBundled();

    @Test
    void shouldActivateEveryCapabilityUpToP2AndKeepLaterPhasesExplainOnly() {
        assertEquals("P2", CapabilityRegistry.CURRENT_PHASE);
        for (String id : List.of("messaging", "scheduler", "spring-batch", "db-migration", "security", "caching")) {
            assertTrue(registry.capability(id).activeIn(CapabilityRegistry.CURRENT_PHASE), id);
        }
        assertFalse(registry.capability("grpc-api").activeIn(CapabilityRegistry.CURRENT_PHASE), "P3");
    }

    @Test
    void shouldActivateRestApisInP0AndKeepLaterCapabilitiesExplainOnly() {
        CapabilityRegistry.Capability rest = registry.capability("rest-api");

        assertTrue(rest.activeIn("P0"));
        assertEquals("component", rest.defaultLayer());
        assertTrue(
                registry.capabilities.stream().anyMatch(c -> !c.activeIn(CapabilityRegistry.CURRENT_PHASE)),
                "later phases exist");
        assertEquals(4, CapabilityRegistry.phaseNumber("P4"));
    }

    @Test
    void shouldListTheSkillsOfACapabilityAndRefuseAnUnknownOne() {
        assertFalse(registry.skillsFor("rest-api", Set.of("rest-api")).isEmpty());
        assertThrows(GtaException.class, () -> registry.capability("teleport"));
    }

    @Test
    void shouldAddCrossCuttingSkillsOnlyForDetectedCapabilities() {
        assertFalse(registry.capability("security").dispatch, "security never produces flows of its own");
        assertTrue(
                registry.skillsFor("rest-api", Set.of("rest-api", "security")).contains("gta-testing-spring-security"));
        assertFalse(registry.skillsFor("rest-api", Set.of("rest-api")).contains("gta-testing-spring-security"));
        assertTrue(registry.skillsFor("rest-api", Set.of("caching")).contains("gta-testing-caches"));
    }

    @Test
    void shouldLetARepositoryOptIntoALaterPhaseButNeverAnEarlierOne() {
        assertEquals(CapabilityRegistry.CURRENT_PHASE, CapabilityRegistry.effectivePhase(null));
        assertEquals("P3", CapabilityRegistry.effectivePhase("P3"), "a pilot service previews P3");
        assertEquals(CapabilityRegistry.CURRENT_PHASE, CapabilityRegistry.effectivePhase("P0"), "never below current");
        assertThrows(com.gta.core.error.GtaException.class, () -> CapabilityRegistry.effectivePhase("phase3"));
        assertTrue(registry.capability("webflux").activeIn(CapabilityRegistry.effectivePhase("P3")));
    }
}
