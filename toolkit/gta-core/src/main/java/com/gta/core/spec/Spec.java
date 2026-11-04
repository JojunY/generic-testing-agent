package com.gta.core.spec;

import com.gta.core.util.Hashes;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsed behaviour spec (plan §3.3). Text remains the source of truth; this is a read model. */
public final class Spec {
    public static final List<String> STATUSES = List.of("draft", "in-review", "approved", "stale", "withdrawn");
    public static final List<String> SOURCES = List.of("observed", "documented", "confirmed", "defaulted", "assumed");

    public Path path;
    public String text;
    public String title;
    public CommentMeta header = new CommentMeta(null, List.of());
    public List<String> sections = new ArrayList<>();
    public List<Requirement> requirements = new ArrayList<>();
    public List<Invariant> invariants = new ArrayList<>();
    public List<Question> questions = new ArrayList<>();
    public String questionRound;
    public List<Finding> findings = new ArrayList<>();
    public List<CommentMeta> answers = new ArrayList<>();
    public List<CommentMeta> approvals = new ArrayList<>();
    public List<String> mermaidBlocks = new ArrayList<>();

    public String flowId() {
        return header.get("flowId").orElse(null);
    }

    public String status() {
        return header.getOr("status", "draft");
    }

    public String rev() {
        return header.getOr("rev", "R0");
    }

    public String capability() {
        return header.getOr("capability", "rest-api");
    }

    public int risk() {
        try {
            return Integer.parseInt(header.getOr("risk", "4"));
        } catch (NumberFormatException e) {
            return 4;
        }
    }

    public List<Scenario> scenarios() {
        List<Scenario> out = new ArrayList<>();
        requirements.forEach(r -> out.addAll(r.scenarios));
        return out;
    }

    public Optional<Scenario> scenario(String id) {
        return scenarios().stream().filter(s -> id.equals(s.id())).findFirst();
    }

    public Optional<Requirement> requirementOf(Scenario scenario) {
        return requirements.stream().filter(r -> r.scenarios.contains(scenario)).findFirst();
    }

    public List<Question> openQuestions() {
        return questions.stream().filter(q -> !q.answered()).toList();
    }

    public static final class Requirement {
        public String title;
        public CommentMeta meta = new CommentMeta(null, List.of());
        public List<String> textLines = new ArrayList<>();
        public List<Scenario> scenarios = new ArrayList<>();
        /** Raw block text (heading to next requirement), used for approval hashes. */
        public String block = "";

        public String id() {
            return meta.get("id").orElse(null);
        }

        public String text() {
            return String.join(" ", textLines).trim();
        }

        public List<String> refs() {
            return meta.getAll("ref");
        }
    }

    public static final class Scenario {
        private static final Pattern BACKTICK = Pattern.compile("`([^`]+)`");
        public String title;
        public CommentMeta meta = new CommentMeta(null, List.of());
        public List<String> whens = new ArrayList<>();
        public List<String> thens = new ArrayList<>();
        public String inheritedSource = "observed";

        public String id() {
            return meta.get("id").orElse(null);
        }

        public String source() {
            return meta.get("source").orElse(inheritedSource);
        }

        /** Expected-value literals written in backticks in THEN clauses (plan §3.3, §5.3). */
        public List<String> thenLiterals() {
            List<String> out = new ArrayList<>();
            for (String t : thens) {
                Matcher m = BACKTICK.matcher(t);
                while (m.find()) {
                    out.add(m.group(1).trim());
                }
            }
            return out;
        }

        public boolean reviewerChecked() {
            return "reviewer".equals(meta.getOr("check", ""));
        }

        /**
         * Hash of what the scenario promises (title, WHEN, THEN, source). A test locked on one revision is re-verified when
         * the revision changes; whitespace and case do not count.
         */
        public String revision() {
            return Hashes.normalizedShort(
                    title + "|" + String.join("|", whens) + "|" + String.join("|", thens) + "|" + source());
        }
    }

    public static final class Invariant {
        public String text;
        public CommentMeta meta = new CommentMeta(null, List.of());

        public String id() {
            return meta.get("id").orElse(null);
        }
    }

    public static final class Question {
        public String number;
        public String text;
        public CommentMeta meta = new CommentMeta(null, List.of());
        public List<Option> options = new ArrayList<>();

        public String id() {
            return meta.get("id").orElse(null);
        }

        public String impact() {
            return meta.getOr("impact", "normal");
        }

        public List<String> affects() {
            return List.of(meta.getOr("affects", "").split(",")).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }

        public boolean answered() {
            return options.stream().anyMatch(o -> o.checked);
        }

        public Optional<Option> chosen() {
            return options.stream().filter(o -> o.checked).findFirst();
        }

        public Optional<Option> currentCodeOption() {
            return options.stream().filter(Option::isCurrentCode).findFirst();
        }
    }

    public static final class Option {
        public String letter;
        public String text;
        public boolean checked;

        public boolean isCurrentCode() {
            return text.toLowerCase().contains("= current code")
                    || text.contains("= 代码现状"); // the Chinese marker is an accepted alias
        }
    }

    public static final class Finding {
        public String text;
        public CommentMeta meta = new CommentMeta(null, List.of());
    }
}
