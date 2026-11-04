package com.gta.core.registry;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.util.Yaml;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** core/registry/capabilities.yaml, bundled into the jar under gta/registry/ (plan §4.4). */
public class Registry {
    public String schemaVersion;
    public List<Capability> capabilities = new ArrayList<>();
    public Always always = new Always();

    public static class Capability {
        public String id;
        public String since = "P0";
        public boolean dispatch = true;
        public Detect detect = new Detect();
        public List<String> layers = new ArrayList<>();
        public List<String> diagrams = new ArrayList<>();
        public List<String> skills = new ArrayList<>();

        public boolean activeIn(String phase) {
            return phaseNumber(since) <= phaseNumber(phase);
        }

        public String defaultLayer() {
            if (layers.contains("component")) {
                return "component";
            }
            return layers.isEmpty() ? "unit" : layers.get(0);
        }
    }

    public static class Detect {
        public List<String> buildDeps = new ArrayList<>();
        public List<String> annotations = new ArrayList<>();
        public List<String> files = new ArrayList<>();
    }

    public static class Always {
        public List<String> skills = new ArrayList<>();
    }

    /** The phase this build of gta implements. Capabilities with a later `since` are explain-only. */
    public static final String CURRENT_PHASE = "P0";

    public static int phaseNumber(String phase) {
        try {
            return Integer.parseInt(phase.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 99;
        }
    }

    public static Registry loadBundled() {
        try (InputStream in = Registry.class.getResourceAsStream("/gta/registry/capabilities.yaml")) {
            if (in == null) {
                throw new GtaException(ErrorCode.INTERNAL_ERROR, "Bundled registry not found", "Rebuild gta-cli");
            }
            return Yaml.MAPPER.readValue(in, Registry.class);
        } catch (IOException e) {
            throw new GtaException(
                    ErrorCode.INTERNAL_ERROR,
                    "Cannot parse registry: " + e.getMessage(),
                    "Fix core/registry/capabilities.yaml");
        }
    }

    public Capability capability(String id) {
        return capabilities.stream()
                .filter(c -> c.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new GtaException(
                        ErrorCode.USAGE_INVALID_ARGUMENT,
                        "Unknown capability " + id,
                        "Use one of the ids in core/registry/capabilities.yaml"));
    }

    /** Skills for an item: always-skills + capability skills + non-dispatching capabilities that were detected. */
    public List<String> skillsFor(String capabilityId, Set<String> detectedCapabilities) {
        Set<String> skills = new LinkedHashSet<>(always.skills);
        skills.addAll(capability(capabilityId).skills);
        for (Capability c : capabilities) {
            if (!c.dispatch && detectedCapabilities.contains(c.id)) {
                skills.addAll(c.skills);
            }
        }
        return new ArrayList<>(skills);
    }
}
