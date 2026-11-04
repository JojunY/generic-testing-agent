package com.gta.core.oracle;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
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

    /**
     * Reads a committed shard. A shard that cannot be parsed (e.g. merge-conflict markers) fails closed: treating it as
     * empty would let the next commit overwrite every other locked oracle and let the gate skip the flow.
     */
    public static Map<String, Entry> read(Path file) {
        if (!Files.exists(file)) {
            return new TreeMap<>();
        }
        try {
            return parse(Files.readString(file), file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Map<String, Entry> parse(String json, String where) {
        try {
            Map<String, Entry> m = Json.MAPPER.readValue(json, new TypeReference<TreeMap<String, Entry>>() {
            });
            return m == null ? new TreeMap<>() : m;
        } catch (IOException e) {
            throw new GtaException(ErrorCode.CONFIG_ORACLE_SHARD_INVALID, "Oracle shard " + where + " cannot be read: " + e.getMessage(),
                    "Resolve the merge conflict or restore the file from git (both sides' entries must be kept), then re-run", e);
        }
    }

    public static void write(Path file, Map<String, Entry> entries) {
        Json.writeAtomic(file, new TreeMap<>(entries));
    }
}
