package com.gta.core.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashes {
    private Hashes() {}

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(Path file) {
        try {
            return sha256(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Short form used in specs and shards (12 hex chars is plenty for change detection). */
    public static String shortSha(String text) {
        return sha256(text).substring(0, 12);
    }

    /** Whitespace-insensitive hash, used for method bodies and requirement blocks. */
    public static String normalizedShort(String text) {
        return shortSha(text.replaceAll("\\s+", ""));
    }
}
