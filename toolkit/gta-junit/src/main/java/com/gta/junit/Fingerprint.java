package com.gta.junit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Failure fingerprint shared with gtaw: exception type + message with numbers and hex ids normalised. */
public final class Fingerprint {
    private Fingerprint() {
    }

    public static String of(String exceptionType, String message) {
        String m = message == null ? "" : message.replaceAll("0x[0-9a-fA-F]+|[0-9a-f]{8,}|\\d+", "#").replaceAll("\\s+", " ").trim();
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
