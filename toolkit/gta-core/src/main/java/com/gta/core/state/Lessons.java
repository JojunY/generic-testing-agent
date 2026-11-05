package com.gta.core.state;

import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.util.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The learning loop (plan §12, P2): what went wrong in earlier runs, recorded by gtaw when it happens (a reviewer's
 * reject, a test that could not fail, a corrected expectation, a confirmed defect) in the tracked {@code
 * .gta/lessons.md}. Briefs show the latest lessons so the next author or analyst does not repeat them. Each lesson is
 * a fact gtaw saw, never a summary an LLM wrote.
 */
public final class Lessons {
    static final String HEADER = "# Lessons from earlier gta runs\n\n"
            + "Written by gtaw when a reviewer rejects a test, a test cannot fail, a person corrects an expectation or a"
            + " defect is confirmed. People may edit or delete lines; gtaw only appends.\n\n";

    private Lessons() {}

    /** Appends {@code - [kind] where: text} unless the same lesson of the same kind is already there. */
    public static void record(GtaPaths paths, String kind, String where, String text) {
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        if (clean.isEmpty()) {
            return;
        }
        Path file = paths.lessons();
        String current = read(file);
        if (current.contains("[" + kind + "] ") && current.contains(": " + clean + "\n")) {
            return;
        }
        String body = current.isEmpty() ? HEADER : current.endsWith("\n") ? current : current + "\n";
        AtomicFiles.write(file, body + "- [" + kind + "] " + where + ": " + clean + "\n");
    }

    /** The newest {@code max} lessons, newest last. */
    public static List<String> recent(GtaPaths paths, int max) {
        List<String> lines =
                read(paths.lessons()).lines().filter(l -> l.startsWith("- [")).toList();
        return lines.subList(Math.max(0, lines.size() - max), lines.size());
    }

    private static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new GtaException(
                    ErrorCode.STATE_CORRUPT,
                    "Cannot read " + file + ": " + e.getMessage(),
                    "Fix or delete .gta/lessons.md; it only holds hints for briefs");
        }
    }
}
