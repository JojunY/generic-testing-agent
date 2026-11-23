package com.gta.core.util;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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

    public static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replaceAll("[^A-Za-z0-9]+", "-")
                .toLowerCase(Locale.ROOT)
                .replaceAll("^-|-$", "");
    }

    /**
     * The flow name without the words that repeat its domain (plan §9.4): {@code borrow.book-borrow} becomes
     * {@code borrow.book}, {@code stock-loan.book-stock-loan} becomes {@code stock-loan.book}. Unchanged when the domain
     * words do not appear in the name, nothing else would be left, only a listener's {@code on} would, or what is left
     * does not start with a letter.
     */
    public static String withoutRepeatedDomain(String flowId) {
        int dot = flowId.indexOf('.');
        if (dot < 0) {
            return flowId;
        }
        List<String> domain = List.of(flowId.substring(0, dot).split("-"));
        List<String> name = new ArrayList<>(List.of(flowId.substring(dot + 1).split("-")));
        for (int i = 0; i + domain.size() <= name.size(); i++) {
            if (name.subList(i, i + domain.size()).equals(domain)) {
                if (name.size() == domain.size()
                        || (name.size() == domain.size() + 1 && name.get(0).equals("on"))) {
                    return flowId; // nothing left, or a listener's bare "on": payment.on-payment says more than
                    // payment.on
                }
                name.subList(i, i + domain.size()).clear();
                if (!name.get(0).matches("[a-z].*")) {
                    return flowId; // search.search-2 must not become search.2: a flow name starts with a letter
                }
                return flowId.substring(0, dot + 1) + String.join("-", name);
            }
        }
        return flowId;
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
