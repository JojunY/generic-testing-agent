package com.gta.core.util;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/** ID formats of plan §9.4. */
public final class Ids {
    public static final Pattern FLOW_ID = Pattern.compile("^[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*$");
    public static final Pattern SPEC_ELEMENT_ID = Pattern.compile("^[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*/[rsiqf]\\d{2}$");
    public static final Pattern ITEM_ID = Pattern.compile(
            "^([a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*/[si]\\d{2})?)#(unit|slice|component|contract|system|foundation)-\\d{2}$");
    public static final Pattern AGENT_NAME = Pattern.compile("^gta-[a-z]+(-[a-z]+)*$");
    public static final Pattern SKILL_NAME = Pattern.compile("^gta-[a-z0-9]+(-[a-z0-9]+)*$");
    public static final Pattern REVISION = Pattern.compile("^R\\d+$");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT);
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";

    private Ids() {}

    public static String requireFlowId(String flowId) {
        if (flowId == null || !FLOW_ID.matcher(flowId).matches()) {
            throw new GtaException(
                    ErrorCode.USAGE_INVALID_ARGUMENT,
                    "Invalid flowId: " + flowId,
                    "A flowId has exactly two segments: <domain>.<flow-name>, e.g. order.create");
        }
        if (flowId.startsWith("foundation.")) {
            throw new GtaException(
                    ErrorCode.USAGE_INVALID_ARGUMENT, "'foundation' is a reserved domain", "Pick another domain name");
        }
        return flowId;
    }

    public static String domainOf(String flowId) {
        return flowId.substring(0, flowId.indexOf('.'));
    }

    public static String flowNameOf(String flowId) {
        return flowId.substring(flowId.indexOf('.') + 1);
    }

    /** File-name safe form of an itemId: '/' and '#' become '__'. */
    public static String itemKey(String itemId) {
        return itemId.replace("/", "__").replace("#", "__");
    }

    public static String rand4() {
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public static String newSessionId() {
        return "s" + ZonedDateTime.now(ZoneOffset.UTC).format(TS) + "-" + rand4();
    }

    public static String newRunId(String headSha) {
        String sha = headSha == null || headSha.length() < 7 ? "0000000" : headSha.substring(0, 7);
        return "r" + ZonedDateTime.now(ZoneOffset.UTC).format(TS) + "-" + sha + "-" + rand4();
    }

    /** mmddhhmmss-rand4 extracted from a runId (used for cloud resources and Oracle schemas). */
    public static String shortRunId(String runId) {
        String[] parts = runId.split("-");
        String ts = parts[0].substring(1);
        return ts.substring(4) + "-" + parts[parts.length - 1];
    }

    public static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replaceAll("[^A-Za-z0-9]+", "-")
                .toLowerCase(Locale.ROOT)
                .replaceAll("^-|-$", "");
    }

    public static String pascal(String kebab) {
        StringBuilder sb = new StringBuilder();
        for (String part : kebab.split("[-_.]")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb.toString();
    }
}
