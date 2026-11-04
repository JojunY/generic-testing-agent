package com.gta.core.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text edits that only gtaw performs on specs: status/rev, sources, ref hashes, ticks, answer and approval records.
 * Every edit rewrites a single gta comment or a single option line, leaving the human-written prose untouched.
 */
public final class SpecEditor {
    private static final Pattern COMMENT = Pattern.compile("<!--\\s*gta:(.*?)-->", Pattern.DOTALL);
    private static final Pattern OPTION = Pattern.compile("^(\\s*-\\s*\\[)( |x|X)(]\\s*([A-Z])\\).*)$");

    private String text;

    public SpecEditor(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    /** Rewrites the first gta comment for which the predicate matches. */
    private boolean rewriteComment(Predicate<CommentMeta> match, UnaryOperator<CommentMeta> change) {
        Matcher m = COMMENT.matcher(text);
        while (m.find()) {
            CommentMeta meta = CommentMeta.parseInner(m.group(1));
            if (match.test(meta)) {
                text = text.substring(0, m.start()) + change.apply(meta).format() + text.substring(m.end());
                return true;
            }
        }
        return false;
    }

    public SpecEditor setHeader(String key, String value) {
        rewriteComment(m -> m.get("flowId").isPresent(), m -> m.with(key, value));
        return this;
    }

    public SpecEditor setElement(String id, String key, String value) {
        rewriteComment(m -> id.equals(m.get("id").orElse(null)), m -> m.with(key, value));
        return this;
    }

    public SpecEditor setRefHashes(String requirementId, Map<String, String> hashByRef) {
        rewriteComment(m -> requirementId.equals(m.get("id").orElse(null)), m -> {
            List<CommentMeta.Entry> out = new ArrayList<>();
            List<CommentMeta.Entry> in = m.entries();
            for (int i = 0; i < in.size(); i++) {
                CommentMeta.Entry e = in.get(i);
                if (e.key().equals("refHash")) {
                    continue; // re-emitted right after its ref
                }
                out.add(e);
                if (e.key().equals("ref") && hashByRef.containsKey(e.value())) {
                    out.add(new CommentMeta.Entry("refHash", hashByRef.get(e.value())));
                }
            }
            return m.withEntries(out);
        });
        return this;
    }

    /** Ticks exactly one option of a question (all others are unticked). */
    public SpecEditor tick(String questionId, String letter) {
        String[] lines = text.split("\\R", -1);
        boolean inQuestion = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("gta:") && line.contains("<!--")) {
                inQuestion = CommentMeta.parse(line)
                        .flatMap(m -> m.get("id"))
                        .map(questionId::equals)
                        .orElse(false);
                continue;
            }
            if (line.trim().startsWith("## ")) {
                inQuestion = false;
            }
            if (inQuestion) {
                Matcher o = OPTION.matcher(line);
                if (o.find()) {
                    lines[i] = o.group(1) + (o.group(4).equals(letter) ? "x" : " ") + o.group(3);
                }
            }
        }
        text = String.join("\n", lines);
        return this;
    }

    public SpecEditor setRound(String round) {
        if (!rewriteComment(m -> m.get("round").isPresent() && m.get("id").isEmpty(), m -> m.with("round", round))) {
            text = text.replaceFirst(
                    "(?m)^## Open questions\\s*$", "## Open questions\n<!-- gta: round=" + round + " -->");
        }
        return this;
    }

    public SpecEditor appendRecord(CommentMeta record) {
        if (!text.contains("\n## Answers")) {
            text = text.stripTrailing() + "\n\n## Answers\n";
        }
        text = text.stripTrailing() + "\n" + record.format() + "\n";
        return this;
    }

    /** Replaces the literal inside backticks of a THEN clause of one scenario (observation auto-correct, plan §3.2). */
    public SpecEditor replaceThenLiteral(String scenarioId, String from, String to) {
        int idx = text.indexOf("id=" + scenarioId);
        if (idx < 0) {
            return this;
        }
        int end = text.indexOf("#### Scenario:", idx + 1);
        int nextReq = text.indexOf("### Requirement:", idx + 1);
        int nextSection = text.indexOf("\n## ", idx + 1);
        int stop = text.length();
        for (int candidate : new int[] {end, nextReq, nextSection}) {
            if (candidate > 0 && candidate < stop) {
                stop = candidate;
            }
        }
        String block = text.substring(idx, stop).replace("`" + from + "`", "`" + to + "`");
        text = text.substring(0, idx) + block + text.substring(stop);
        return this;
    }
}
