package com.gta.harness;

import com.gta.core.util.Yaml;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** A markdown file with a YAML frontmatter block: parse it, and emit frontmatter in a stable, readable form. */
public record Frontmatter(Map<String, Object> fields, String body) {

    private static final Pattern PLAIN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 _./()+,-]*$");
    private static final Set<String> RESERVED = Set.of("true", "false", "null", "yes", "no", "on", "off", "~");

    @SuppressWarnings("unchecked")
    public static Frontmatter parse(String text) {
        String t = text.replace("\r\n", "\n");
        if (!t.startsWith("---\n")) {
            return new Frontmatter(new LinkedHashMap<>(), t);
        }
        int end = t.indexOf("\n---\n", 3);
        if (end < 0) {
            throw new IllegalArgumentException("Unterminated frontmatter");
        }
        String yaml = t.substring(4, end + 1);
        String body = t.substring(end + 5);
        try {
            Map<String, Object> map =
                    yaml.isBlank() ? new LinkedHashMap<>() : Yaml.MAPPER.readValue(yaml, LinkedHashMap.class);
            return new Frontmatter(map, body);
        } catch (IOException e) {
            throw new UncheckedIOException("Invalid frontmatter: " + e.getMessage(), e);
        }
    }

    public String render() {
        StringBuilder sb = new StringBuilder("---\n");
        emitMap(sb, fields, 0);
        sb.append("---\n");
        if (!body.startsWith("\n")) {
            sb.append('\n');
        }
        sb.append(body);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void emitMap(StringBuilder sb, Map<String, Object> map, int indent) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            sb.append(" ".repeat(indent)).append(scalar(e.getKey())).append(':');
            if (v instanceof Map<?, ?> m) {
                if (m.isEmpty()) {
                    sb.append(" {}\n");
                } else {
                    sb.append('\n');
                    emitMap(sb, (Map<String, Object>) m, indent + 2);
                }
            } else if (v instanceof List<?> l) {
                sb.append(" [");
                for (int i = 0; i < l.size(); i++) {
                    sb.append(i == 0 ? "" : ", ").append(scalar(l.get(i)));
                }
                sb.append("]\n");
            } else {
                sb.append(' ').append(scalar(v)).append('\n');
            }
        }
    }

    static String scalar(Object v) {
        if (v instanceof Boolean || v instanceof Number) {
            return v.toString();
        }
        String s = String.valueOf(v);
        if (PLAIN.matcher(s).matches()
                && !RESERVED.contains(s.toLowerCase())
                && !s.endsWith(" ")
                && !s.matches("^[0-9.]+$")) {
            return s;
        }
        StringBuilder q = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> q.append("\\\"");
                case '\\' -> q.append("\\\\");
                case '\n' -> q.append("\\n");
                case '\t' -> q.append("\\t");
                default -> q.append(c);
            }
        }
        return q.append('"').toString();
    }
}
