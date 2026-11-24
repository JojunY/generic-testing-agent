package com.gta.core.engine;

import com.gta.core.state.WorkState;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** The JSON shapes gtaw prints: agent/human nodes, done and plain results. */
final class NodeResults {
    private NodeResults() {}

    static Map<String, Object> node(String node, Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", node);
        out.putAll(extra);
        return out;
    }

    static Map<String, Object> done(String message, Map<String, Object> more) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("message", message);
        if (more != null) {
            extra.putAll(more);
        }
        return node("done", extra);
    }

    static Map<String, Object> result(String message, Map<String, Object> more) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", message);
        if (more != null) {
            out.putAll(more);
        }
        return out;
    }

    static Map<String, Object> answerNode(WorkState.Pending p) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("type", p.type);
        if (p.flowId != null) {
            extra.put("flowId", p.flowId);
        }
        if (p.itemId != null) {
            extra.put("itemId", p.itemId);
        }
        extra.put("prompt", p.prompt);
        extra.put(
                "instruction",
                "Show display (the status block, which contains the prompt) to the human verbatim, then pass their reply"
                        + " verbatim to ./gtaw answer \"<reply>\"");
        return node("answer", extra);
    }

    /**
     * A failure message on one line for the status block: its first three non-blank lines, joined, so that an
     * assertion's "expected: … but was: …" stays with its description.
     */
    static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        return text.lines()
                .map(String::strip)
                .filter(l -> !l.isEmpty())
                .limit(3)
                .collect(java.util.stream.Collectors.joining(" "));
    }

    static String firstLines(String text, int n) {
        if (text == null) {
            return "";
        }
        String[] lines = text.split("\\R");
        return String.join("\n", Arrays.copyOf(lines, Math.min(n, lines.length)));
    }
}
