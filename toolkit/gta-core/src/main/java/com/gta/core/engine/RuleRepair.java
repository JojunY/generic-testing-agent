package com.gta.core.engine;

import com.gta.core.plan.PlanItem;
import com.gta.core.util.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rules before the LLM (plan §2, P2): slips gtaw can fix deterministically in the item's own test file, so a repair
 * round is not spent on them. Each repair is reported to the author and recorded as a lesson. A rule never touches an
 * assertion, so the oracle lock is unaffected.
 */
final class RuleRepair {
    /** Types tests use most, for "cannot find symbol: class X" (the author forgot the import). */
    static final Map<String, String> IMPORTS = Map.ofEntries(
            Map.entry("Test", "org.junit.jupiter.api.Test"),
            Map.entry("Tag", "org.junit.jupiter.api.Tag"),
            Map.entry("DisplayName", "org.junit.jupiter.api.DisplayName"),
            Map.entry("BeforeEach", "org.junit.jupiter.api.BeforeEach"),
            Map.entry("Nested", "org.junit.jupiter.api.Nested"),
            Map.entry("KnownDefect", "com.gta.junit.KnownDefect"),
            Map.entry("Autowired", "org.springframework.beans.factory.annotation.Autowired"),
            Map.entry("SpringBootTest", "org.springframework.boot.test.context.SpringBootTest"),
            Map.entry("MockMvc", "org.springframework.test.web.servlet.MockMvc"),
            Map.entry("MediaType", "org.springframework.http.MediaType"),
            Map.entry("BigDecimal", "java.math.BigDecimal"),
            Map.entry("UUID", "java.util.UUID"),
            Map.entry("List", "java.util.List"),
            Map.entry("Map", "java.util.Map"),
            Map.entry("Duration", "java.time.Duration"));

    private static final Pattern MISSING_CLASS = Pattern.compile("symbol:\\s+class\\s+(\\w+)");

    private RuleRepair() {}

    /**
     * @param buildLog the Maven output of the failed run, or "" when the run compiled
     * @param noScenarioTestRan true when no test reported the item's scenario tag
     * @return what was repaired; empty when nothing was
     */
    static List<String> apply(Path testFile, PlanItem item, String buildLog, boolean noScenarioTestRan) {
        if (item.isFoundation() || !Files.exists(testFile)) {
            return List.of();
        }
        String text;
        try {
            text = Files.readString(testFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
        List<String> done = new ArrayList<>();
        String fixed = addMissingImports(text, buildLog, done);
        if (noScenarioTestRan) {
            fixed = addMissingScenarioTag(fixed, item, done);
        }
        if (!done.isEmpty()) {
            AtomicFiles.write(testFile, fixed);
        }
        return done;
    }

    static String addMissingImports(String text, String buildLog, List<String> done) {
        Set<String> missing = new LinkedHashSet<>();
        Matcher m = MISSING_CLASS.matcher(buildLog);
        while (m.find()) {
            String fqcn = IMPORTS.get(m.group(1));
            if (fqcn != null && !text.contains("import " + fqcn + ";")) {
                missing.add(fqcn);
            }
        }
        if (missing.isEmpty()) {
            return text;
        }
        Matcher pkg = Pattern.compile("(?m)^package [\\w.]+;\\s*\\n").matcher(text);
        if (!pkg.find()) {
            return text;
        }
        StringBuilder imports = new StringBuilder();
        for (String fqcn : missing) {
            imports.append("import ").append(fqcn).append(";\n");
            done.add("added the missing import " + fqcn);
        }
        return text.substring(0, pkg.end()) + "\n" + imports + text.substring(pkg.end());
    }

    /**
     * A method whose {@code @DisplayName} names the scenario but carries no {@code @Tag("scenario:...")} is invisible to
     * gtaw's run (it selects by tag): add the scenario and flow tags.
     */
    static String addMissingScenarioTag(String text, PlanItem item, List<String> done) {
        String scenarioTag = "@Tag(\"scenario:" + item.ref + "\")";
        if (text.contains(scenarioTag)) {
            return text;
        }
        Matcher display = Pattern.compile("(?m)^([ \\t]*)@DisplayName\\(\"\\[" + Pattern.quote(item.ref) + "\\]")
                .matcher(text);
        if (!display.find()) {
            return text;
        }
        String indent = display.group(1);
        String flowTag = "@Tag(\"flow:" + item.flowId + "\")";
        String tags = (text.contains(flowTag) ? "" : indent + flowTag + "\n") + indent + scenarioTag + "\n";
        String out = text.substring(0, display.start()) + tags + text.substring(display.start());
        done.add("added " + scenarioTag + " to the method named [" + item.ref + "]");
        if (!out.contains("import org.junit.jupiter.api.Tag;")) {
            out = addMissingImports(out, "symbol: class Tag", done);
        }
        return out;
    }

    /** The intent tags (plan §9.5): every gta test method carries exactly one. */
    static final List<String> INTENT_TAGS = List.of("characterization", "hardening", "reproducing", "catching");

    private static final Pattern MANAGED_TAG =
            Pattern.compile("[ \\t]*@Tag\\(\\s*\"(?:characterization|hardening|reproducing|catching|p[0-3])\"\\s*\\)");

    /**
     * Before every run (plan §9.5): the method tagged with the item's scenario carries exactly one intent tag and one
     * priority tag, the plan's. Tags come from the spec and the plan, never from the author: wrong or duplicate ones are
     * replaced. Assertions are never touched.
     *
     * @return what changed; empty when the tags were already right or the method has no scenario tag
     */
    static List<String> normalizeTags(Path testFile, PlanItem item) {
        if (item.isFoundation() || item.intent == null || item.priority == null || !Files.exists(testFile)) {
            return List.of();
        }
        try {
            String text = Files.readString(testFile, StandardCharsets.UTF_8);
            List<String> done = new ArrayList<>();
            String fixed = normalizeTags(text, item, done);
            if (!done.isEmpty()) {
                AtomicFiles.write(testFile, fixed);
            }
            return done;
        } catch (IOException e) {
            return List.of();
        }
    }

    static String normalizeTags(String text, PlanItem item, List<String> done) {
        Matcher scenario = Pattern.compile("@Tag\\(\\s*\"scenario:" + Pattern.quote(item.ref) + "\"\\s*\\)")
                .matcher(text);
        if (!scenario.find()) {
            return text;
        }
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        int at = (int) text.substring(0, scenario.start())
                .chars()
                .filter(c -> c == '\n')
                .count();
        // the method's annotations: the lines starting with @ around the scenario tag
        int first = at;
        while (first > 0 && lines.get(first - 1).strip().startsWith("@")) {
            first--;
        }
        int last = at;
        while (last + 1 < lines.size() && lines.get(last + 1).strip().startsWith("@")) {
            last++;
        }
        List<String> present = new ArrayList<>();
        for (int i = first; i <= last; i++) {
            Matcher tag = MANAGED_TAG.matcher(lines.get(i));
            while (tag.find()) {
                present.add(tag.group().strip());
            }
        }
        String intentTag = "@Tag(\"" + item.intent + "\")";
        String priorityTag = "@Tag(\"" + item.priority + "\")";
        if (present.equals(List.of(intentTag, priorityTag))) {
            return text;
        }
        String indent = lines.get(at)
                .substring(
                        0, lines.get(at).length() - lines.get(at).stripLeading().length());
        List<String> block = new ArrayList<>();
        for (int i = first; i <= last; i++) {
            String line = MANAGED_TAG.matcher(lines.get(i)).replaceAll("");
            if (!line.isBlank()) {
                block.add(line);
            }
            if (i == at) {
                block.add(indent + intentTag);
                block.add(indent + priorityTag);
            }
        }
        List<String> out = new ArrayList<>(lines.subList(0, first));
        out.addAll(block);
        out.addAll(lines.subList(last + 1, lines.size()));
        done.add("set the tags of [" + item.ref + "] to " + item.intent + " and " + item.priority
                + (present.isEmpty() ? "" : " (was " + String.join(" ", present) + ")"));
        return String.join("\n", out);
    }
}
