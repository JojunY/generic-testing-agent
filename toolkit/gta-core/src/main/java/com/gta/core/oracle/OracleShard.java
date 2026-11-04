package com.gta.core.oracle;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gta.core.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Committed per-flow oracle shard {@code <flow-name>.oracles.json} (plan §5.2). Written only in the same commit as the
 * item's test, so an entry without its test is void.
 */
public final class OracleShard {
    public static class Entry {
        public String scenarioRevision;
        public String fingerprint;
        public List<String> tokens = new ArrayList<>();
        public String testClass;
        public String intent;
        public String source;
        public String relockedBy;
        public String reason;
        /** When the oracle was relocked (ISO-8601); each relock is a new record the gate shows once. */
        public String relockedAt;
    }

    private OracleShard() {
    }

    public static Map<String, Entry> read(Path file) {
        if (!Files.exists(file)) {
            return new TreeMap<>();
        }
        try {
            return parse(Files.readString(file));
        } catch (IOException e) {
            return new TreeMap<>();
        }
    }

    public static Map<String, Entry> parse(String json) {
        try {
            Map<String, Entry> m = Json.MAPPER.readValue(json, new TypeReference<TreeMap<String, Entry>>() {
            });
            return m == null ? new TreeMap<>() : m;
        } catch (IOException e) {
            return new TreeMap<>();
        }
    }

    public static void write(Path file, Map<String, Entry> entries) {
        Json.writeAtomic(file, new TreeMap<>(entries));
    }
}
