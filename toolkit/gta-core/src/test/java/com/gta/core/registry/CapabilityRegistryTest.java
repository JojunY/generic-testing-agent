package com.gta.core.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.error.GtaException;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The bundled capability registry (core/registry/capabilities.yaml, plan §6). */
class CapabilityRegistryTest {
    final CapabilityRegistry registry = CapabilityRegistry.loadBundled();

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
}
