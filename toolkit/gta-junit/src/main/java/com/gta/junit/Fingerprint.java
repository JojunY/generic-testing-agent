package com.gta.junit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Failure fingerprint shared with gtaw: exception type + message with volatile parts normalised. */
public final class Fingerprint {
    /**
     * Volatile parts of a failure message: UUIDs, timestamps, hex ids, identity hashes and long numbers. Short numbers
     * (the expected and actual values) are kept, so a defect that changes its actual value is no longer "known".
     * Must stay identical to FailureClassifier.VOLATILE in gta-core. Word edges are spelled out instead of {@code \b},
     * whose meaning changed in JDK 19: the tests run on the service's JDK, gtaw on its own.
     */
    public static final String VOLATILE =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2})?|0x[0-9a-fA-F]+|@[0-9a-fA-F]{4,}(?![A-Za-z0-9_])|(?<![A-Za-z0-9_])[0-9a-f]{8,}(?![A-Za-z0-9_])|(?<![A-Za-z0-9_])\\d{6,}(?![A-Za-z0-9_])";

    private Fingerprint() {}

    public static String of(String exceptionType, String message) {
        String m = message == null
                ? ""
                : message.replaceAll(VOLATILE, "#").replaceAll("\\s+", " ").trim();
        String input = (exceptionType == null ? "" : exceptionType) + "|" + m;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String of(Throwable t) {
        return of(t.getClass().getName(), t.getMessage());
    }
}
