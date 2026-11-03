package com.gta.core.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Machine-readable metadata stored in HTML comments: {@code <!-- gta: [kind] k=v k="v w" -->}.
 * Keys may repeat (e.g. several {@code ref=}); order is preserved. Tokens without '=' after the first are ignored.
 */
public final class CommentMeta {
    private static final Pattern COMMENT = Pattern.compile("<!--\\s*gta:(.*?)-->", Pattern.DOTALL);
    private static final Pattern PAIR = Pattern.compile("([A-Za-z][\\w.]*)=(\"((?:[^\"\\\\]|\\\\.)*)\"|[^\\s\"]+)");
    private static final Pattern BARE_KIND = Pattern.compile("^\\s*([a-z][a-z-]*)(\\s|$)");

    public record Entry(String key, String value) {
    }

    private final String kind;
    private final List<Entry> entries;

    public CommentMeta(String kind, List<Entry> entries) {
        this.kind = kind;
        this.entries = new ArrayList<>(entries);
    }

    public static boolean isGtaComment(String text) {
        return text.contains("<!--") && text.contains("gta:");
    }

    /** Finds every gta comment in the text, in order. */
    public static List<CommentMeta> findAll(String text) {
        List<CommentMeta> out = new ArrayList<>();
        Matcher m = COMMENT.matcher(text);
        while (m.find()) {
            out.add(parseInner(m.group(1)));
        }
        return out;
    }

    public static Optional<CommentMeta> parse(String text) {
        Matcher m = COMMENT.matcher(text);
        return m.find() ? Optional.of(parseInner(m.group(1))) : Optional.empty();
    }

    static CommentMeta parseInner(String inner) {
        String kind = null;
        Matcher k = BARE_KIND.matcher(inner);
        if (k.find() && !inner.trim().startsWith(k.group(1) + "=")) {
            kind = k.group(1);
        }
        List<Entry> entries = new ArrayList<>();
        Matcher m = PAIR.matcher(inner);
        while (m.find()) {
            String value = m.group(3) != null ? m.group(3).replace("\\\"", "\"") : m.group(2);
            entries.add(new Entry(m.group(1), value));
        }
        return new CommentMeta(kind, entries);
    }

    public String kind() {
        return kind;
    }

    public List<Entry> entries() {
        return entries;
    }

    public Optional<String> get(String key) {
        return entries.stream().filter(e -> e.key().equals(key)).map(Entry::value).findFirst();
    }

    public String getOr(String key, String fallback) {
        return get(key).orElse(fallback);
    }

    public List<String> getAll(String key) {
        return entries.stream().filter(e -> e.key().equals(key)).map(Entry::value).toList();
    }

    /** Replaces the first occurrence of key (or appends it). */
    public CommentMeta with(String key, String value) {
        List<Entry> copy = new ArrayList<>(entries);
        boolean replaced = false;
        for (int i = 0; i < copy.size(); i++) {
            if (copy.get(i).key().equals(key)) {
                copy.set(i, new Entry(key, value));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            copy.add(new Entry(key, value));
        }
        return new CommentMeta(kind, copy);
    }

    public CommentMeta without(String key) {
        return new CommentMeta(kind, entries.stream().filter(e -> !e.key().equals(key)).toList());
    }

    public CommentMeta withEntries(List<Entry> newEntries) {
        return new CommentMeta(kind, newEntries);
    }

    public String format() {
        StringBuilder sb = new StringBuilder("<!-- gta:");
        if (kind != null) {
            sb.append(' ').append(kind);
        }
        for (Entry e : entries) {
            sb.append(' ').append(e.key()).append('=');
            String v = e.value();
            if (v.isEmpty() || v.matches(".*[\\s\"].*")) {
                sb.append('"').append(v.replace("\"", "\\\"")).append('"');
            } else {
                sb.append(v);
            }
        }
        return sb.append(" -->").toString();
    }
}
