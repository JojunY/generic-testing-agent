package com.gta.core.spec;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses what the human typed (plan §3.5), passed verbatim by the orchestrator to {@code gtaw answer}.
 * <ul>
 *   <li>Answers: {@code "R3: 1A 2B 其余默认"} (round prefix optional, "rest default" accepted)</li>
 *   <li>Approval: {@code "批准 order.create@R4"} / {@code "approve order.create@R4"}</li>
 *   <li>Choice for a pending decision (defect confirmation, escalation): a single letter, e.g. {@code "A"}</li>
 * </ul>
 */
public final class AnswerParser {
    private static final Pattern APPROVAL = Pattern.compile("(?:批准|approve)\\s+([a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*)@(R\\d+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ROUND = Pattern.compile("^\\s*(R\\d+)\\s*[:：]", Pattern.CASE_INSENSITIVE);
    private static final Pattern PICK = Pattern.compile("(?<![A-Za-z0-9])(\\d{1,2})\\s*([A-Za-z])(?![A-Za-z])");
    private static final Pattern REST_DEFAULT = Pattern.compile("其余默认|其他默认|rest\\s+default", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHOICE = Pattern.compile("^\\s*([A-Za-z])\\s*[).、]?\\s*$");

    public sealed interface Parsed permits Answers, Approval, Choice {
    }

    public record Answers(String round, Map<String, String> picks, boolean restDefault) implements Parsed {
    }

    public record Approval(String flowId, String rev) implements Parsed {
    }

    public record Choice(String letter) implements Parsed {
    }

    private AnswerParser() {
    }

    public static Parsed parse(String text) {
        Matcher a = APPROVAL.matcher(text);
        if (a.find()) {
            return new Approval(a.group(1), a.group(2).toUpperCase());
        }
        Matcher c = CHOICE.matcher(text);
        if (c.find()) {
            return new Choice(c.group(1).toUpperCase());
        }
        String round = null;
        Matcher r = ROUND.matcher(text);
        String body = text;
        if (r.find()) {
            round = r.group(1).toUpperCase();
            body = text.substring(r.end());
        }
        Map<String, String> picks = new LinkedHashMap<>();
        Matcher p = PICK.matcher(body);
        while (p.find()) {
            picks.put(p.group(1), p.group(2).toUpperCase());
        }
        boolean rest = REST_DEFAULT.matcher(body).find();
        if (picks.isEmpty() && !rest) {
            boolean garbled = text.indexOf('\uFFFD') >= 0 || text.matches(".*\\?{2,}.*");
            throw new GtaException(ErrorCode.USAGE_UNPARSEABLE_ANSWER, "Cannot understand the answer: " + text,
                    garbled
                            ? "The terminal passed non-ASCII text in another encoding. Use the ASCII forms ('approve order.create@R4',"
                              + " 'R3: 1A 2B rest default') or write the reply to a UTF-8 file and run ./gtaw answer --file <path>"
                            : "Answer like 'R3: 1A 2B 其余默认', approve like '批准 order.create@R4', or pick an option letter like 'A'");
        }
        return new Answers(round, picks, rest);
    }
}
