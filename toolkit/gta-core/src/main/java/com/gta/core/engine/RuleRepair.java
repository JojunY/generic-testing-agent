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
}
