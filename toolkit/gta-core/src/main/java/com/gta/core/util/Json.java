package com.gta.core.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/** The JSON mapper used for all gta files (stable key order, no nulls) and atomic JSON writes. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private Json() {}

    public static <T> T read(Path path, Class<T> type) {
        try {
            return MAPPER.readValue(path.toFile(), type);
        } catch (IOException e) {
            throw new GtaException(
                    ErrorCode.CONFIG_INVALID,
                    "Cannot read " + path + ": " + e.getMessage(),
                    "Fix or delete the file; files under .gta/work/ are rebuilt by ./gtaw next",
                    e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Serialises a value as pretty JSON and writes it atomically. */
    public static void writeAtomic(Path path, Object value) {
        AtomicFiles.write(path, write(value) + "\n");
    }
}
