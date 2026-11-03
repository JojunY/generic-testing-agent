package com.gta.core.spec;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Line-based parser for the OpenSpec-compatible behaviour spec format (plan §3.3). */
public final class SpecParser {
    private static final Pattern QUESTION = Pattern.compile("^\\s*(\\d+)\\.\\s+(.*)$");
    private static final Pattern OPTION = Pattern.compile("^\\s*-\\s*\\[( |x|X)]\\s*([A-Z])\\)\\s*(.*)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*-\\s*\\*\\*(GIVEN|WHEN|THEN|AND|BUT)\\*\\*\\s*(.*)$");

    private SpecParser() {
    }

    public static Spec parse(Path path) {
        try {
            Spec spec = parseText(Files.readString(path, StandardCharsets.UTF_8));
            spec.path = path;
            return spec;
        } catch (IOException e) {
            throw new GtaException(ErrorCode.USAGE_UNKNOWN_FLOW, "Cannot read spec " + path, "Run ./gtaw next to create it");
        }
    }

    public static Spec parseText(String text) {
        Spec spec = new Spec();
        spec.text = text;
        List<String> lines = joinComments(text);

        String section = "";
        Spec.Requirement req = null;
        Spec.Scenario scenario = null;
        String bulletMode = null;
        Spec.Question question = null;
        CommentMeta pendingQuestionMeta = null;
        CommentMeta pendingFindingMeta = null;
        boolean inMermaid = false;
        StringBuilder mermaid = new StringBuilder();
        StringBuilder reqBlock = null;
        boolean headerSeen = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (inMermaid) {
                if (trimmed.startsWith("```")) {
                    spec.mermaidBlocks.add(mermaid.toString());
                    inMermaid = false;
                } else {
                    mermaid.append(line).append('\n');
                }
                continue;
            }
            if (trimmed.startsWith("```mermaid")) {
                inMermaid = true;
                mermaid.setLength(0);
                continue;
            }
            if (trimmed.startsWith("# ") && spec.title == null) {
                spec.title = trimmed.substring(2).trim();
                continue;
            }
            if (trimmed.startsWith("## ")) {
                section = trimmed.substring(3).trim().toLowerCase();
                spec.sections.add(section);
                req = null;
                scenario = null;
                question = null;
                reqBlock = null;
                continue;
            }
            boolean isComment = CommentMeta.isGtaComment(trimmed) && trimmed.startsWith("<!--");
            if (!headerSeen && isComment && section.isEmpty()) {
                spec.header = CommentMeta.parse(trimmed).orElseThrow();
                headerSeen = true;
                continue;
            }
            if (reqBlock != null && !trimmed.startsWith("### ")) {
                reqBlock.append(line).append('\n');
            }
            switch (section) {
                case "requirements" -> {
                    if (trimmed.startsWith("### Requirement:")) {
                        req = new Spec.Requirement();
                        req.title = trimmed.substring("### Requirement:".length()).trim();
                        spec.requirements.add(req);
                        reqBlock = new StringBuilder(line).append('\n');
                        req.block = "";
                        scenario = null;
                        bulletMode = null;
                    } else if (trimmed.startsWith("#### Scenario:") && req != null) {
                        scenario = new Spec.Scenario();
                        scenario.title = trimmed.substring("#### Scenario:".length()).trim();
                        req.scenarios.add(scenario);
                        bulletMode = null;
                    } else if (isComment && scenario != null && scenario.meta.entries().isEmpty()) {
                        scenario.meta = CommentMeta.parse(trimmed).orElseThrow();
                    } else if (isComment && req != null && scenario == null && req.meta.entries().isEmpty()) {
                        req.meta = CommentMeta.parse(trimmed).orElseThrow();
                    } else if (scenario != null) {
                        Matcher b = BULLET.matcher(line);
                        if (b.find()) {
                            String kw = b.group(1);
                            if (kw.equals("WHEN") || kw.equals("GIVEN")) {
                                bulletMode = "when";
                            } else if (kw.equals("THEN")) {
                                bulletMode = "then";
                            }
                            if ("then".equals(bulletMode)) {
                                scenario.thens.add(b.group(2));
                            } else {
                                scenario.whens.add(b.group(2));
                            }
                        }
                    } else if (req != null && !trimmed.isEmpty() && !isComment) {
                        req.textLines.add(trimmed);
                    }
                    if (req != null && reqBlock != null) {
                        req.block = reqBlock.toString();
                    }
                }
                case "invariants" -> {
                    if (trimmed.startsWith("- ")) {
                        Spec.Invariant inv = new Spec.Invariant();
                        int idx = trimmed.indexOf("<!--");
                        inv.text = (idx >= 0 ? trimmed.substring(2, idx) : trimmed.substring(2)).trim();
                        inv.meta = CommentMeta.parse(trimmed).orElse(new CommentMeta(null, List.of()));
                        spec.invariants.add(inv);
                    }
                }
                case "open questions" -> {
                    if (isComment) {
                        CommentMeta meta = CommentMeta.parse(trimmed).orElseThrow();
                        if (meta.get("round").isPresent() && meta.get("id").isEmpty()) {
                            spec.questionRound = meta.get("round").get();
                        } else {
                            pendingQuestionMeta = meta;
                        }
                    } else {
                        Matcher o = OPTION.matcher(line);
                        Matcher q = QUESTION.matcher(line);
                        if (o.find() && question != null) {
                            Spec.Option opt = new Spec.Option();
                            opt.checked = !o.group(1).isBlank();
                            opt.letter = o.group(2);
                            opt.text = o.group(3).trim();
                            question.options.add(opt);
                        } else if (q.find()) {
                            question = new Spec.Question();
                            question.number = q.group(1);
                            question.text = q.group(2).trim();
                            if (pendingQuestionMeta != null) {
                                question.meta = pendingQuestionMeta;
                                pendingQuestionMeta = null;
                            }
                            spec.questions.add(question);
                        }
                    }
                }
                case "findings" -> {
                    if (isComment) {
                        pendingFindingMeta = CommentMeta.parse(trimmed).orElseThrow();
                    } else if (trimmed.startsWith("- ")) {
                        Spec.Finding f = new Spec.Finding();
                        f.text = trimmed.substring(2).trim();
                        if (pendingFindingMeta != null) {
                            f.meta = pendingFindingMeta;
                            pendingFindingMeta = null;
                        }
                        spec.findings.add(f);
                    }
                }
                case "answers" -> {
                    if (isComment) {
                        CommentMeta meta = CommentMeta.parse(trimmed).orElseThrow();
                        if ("answer".equals(meta.kind())) {
                            spec.answers.add(meta);
                        } else if ("approval".equals(meta.kind())) {
                            spec.approvals.add(meta);
                        }
                    }
                }
                default -> {
                    // boundary, access and free text sections are kept as text only
                }
            }
        }
        for (Spec.Requirement r : spec.requirements) {
            String reqSource = r.meta.getOr("source", "observed");
            for (Spec.Scenario s : r.scenarios) {
                s.inheritedSource = reqSource;
            }
        }
        return spec;
    }

    /** Joins multi-line gta comments onto one logical line so the line parser sees them whole. */
    static List<String> joinComments(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder pending = null;
        for (String line : text.split("\\R", -1)) {
            if (pending != null) {
                pending.append(' ').append(line.trim());
                if (line.contains("-->")) {
                    out.add(pending.toString());
                    pending = null;
                }
                continue;
            }
            if (line.contains("<!--") && line.contains("gta:") && !line.contains("-->")) {
                pending = new StringBuilder(line);
                continue;
            }
            out.add(line);
        }
        if (pending != null) {
            out.add(pending.toString());
        }
        return out;
    }
}
