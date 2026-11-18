package com.gta.core.oracle;

import com.gta.core.util.Hashes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The oracle of a Cucumber scenario (plan §5.3): the scenario tagged {@code @scenario:<id>} in a {@code .feature} file
 * under the test resources. Its Then steps are the assertions and their values the expected-value tokens; the
 * Background, the Given and When steps and the step definitions (glue) are its watched inputs. English Gherkin
 * keywords only.
 */
public final class FeatureScanner {
    /** One step: its section ({@code given}, {@code when}, {@code then}; And/But continue the previous one). */
    public record Step(String section, String text, List<String> arguments) {}

    /**
     * One scenario (or outline): its own tags and the ones it inherits from the feature and its rule, without the
     * {@code @}, and the lines of the file it spans, its tags included (0-based, inclusive). The examples of an outline
     * are its cells with their column, {@code fee=3.47}.
     */
    public record Scenario(
            String name,
            Set<String> tags,
            Set<String> inherited,
            int firstLine,
            int lastLine,
            List<Step> steps,
            List<String> examples) {
        public boolean tagged(String tag) {
            return tags.contains(tag) || inherited.contains(tag);
        }
    }

    public record Feature(Set<String> tags, List<Step> background, List<Scenario> scenarios) {}

    private static final Pattern STEP = Pattern.compile("^(Given|When|Then|And|But|\\*)\\s+(.*)$");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"|'([^']*)'");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])-?\\d[\\d,_]*(?:\\.\\d+)?(?![\\w])");
    private static final Pattern CONSTANT = Pattern.compile("\\b[A-Z][A-Z0-9_]+\\b");
    private static final Pattern WORD_BOOLEAN = Pattern.compile("\\b(true|false)\\b");
    private static final Pattern FIXTURE = Pattern.compile("[\\w./-]+\\.(json|sql|ya?ml|xml|txt|csv|properties)");

    private FeatureScanner() {}

    public static Feature parse(String text) {
        String[] lines = text.split("\n", -1);
        Set<String> featureTags = new LinkedHashSet<>();
        Set<String> ruleTags = new LinkedHashSet<>();
        List<Step> background = new ArrayList<>();
        List<Scenario> scenarios = new ArrayList<>();
        Set<String> pending = new LinkedHashSet<>();
        int pendingFrom = -1;
        // the block being read: background, a scenario (with its examples), or nothing
        String block = "";
        String name = null;
        Set<String> tags = null;
        Set<String> inherited = null;
        int first = -1;
        List<Step> steps = null;
        List<String> examples = null;
        List<String> columns = null;
        String section = "given";
        boolean inDocString = false;
        String fence = null;
        StringBuilder doc = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (inDocString) {
                if (line.startsWith(fence)) {
                    inDocString = false;
                    String content = doc.toString().strip();
                    lastStep(block, steps, background)
                            .ifPresent(s -> s.arguments().add(content));
                } else {
                    doc.append(lines[i].strip()).append('\n');
                }
                continue;
            }
            if (line.startsWith("\"\"\"") || line.startsWith("```")) {
                inDocString = true;
                fence = line.substring(0, 3);
                doc = new StringBuilder();
                continue;
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("@")) {
                if (pending.isEmpty()) {
                    pendingFrom = i;
                }
                for (String t : line.replaceAll("\\s#.*$", "").split("\\s+")) {
                    if (t.startsWith("@") && t.length() > 1) {
                        pending.add(t.substring(1));
                    }
                }
                continue;
            }
            String keyword = keyword(line);
            if (keyword != null && !keyword.equals("examples")) {
                if (block.equals("scenario")) {
                    scenarios.add(new Scenario(
                            name,
                            tags,
                            inherited,
                            first,
                            endOf(lines, (pending.isEmpty() ? i : pendingFrom) - 1),
                            steps,
                            examples));
                }
                block = "";
                switch (keyword) {
                    case "feature" -> featureTags.addAll(pending);
                    case "rule" -> {
                        ruleTags.clear();
                        ruleTags.addAll(pending);
                    }
                    case "background" -> block = "background";
                    default -> {
                        block = "scenario";
                        name = line.substring(line.indexOf(':') + 1).strip();
                        tags = new LinkedHashSet<>(pending);
                        inherited = new LinkedHashSet<>(featureTags);
                        inherited.addAll(ruleTags);
                        first = pending.isEmpty() ? i : pendingFrom;
                        steps = new ArrayList<>();
                        examples = new ArrayList<>();
                    }
                }
                section = "given";
                pending.clear();
                continue;
            }
            if ("examples".equals(keyword)) {
                if (inherited != null) {
                    inherited.addAll(pending); // tags on Examples reach the pickles of their rows
                }
                pending.clear();
                columns = null; // the table's first row names its columns
                continue;
            }
            if (line.startsWith("|")) {
                List<String> cells = cells(line);
                Optional<Step> step = lastStep(block, steps, background);
                if (block.equals("scenario") && examplesAfterSteps(lines, i)) {
                    if (columns == null) {
                        columns = cells;
                    } else {
                        for (int c = 0; c < cells.size(); c++) {
                            examples.add((c < columns.size() ? columns.get(c) : "") + "=" + cells.get(c));
                        }
                    }
                } else {
                    step.ifPresent(s -> s.arguments().add(String.join("|", cells)));
                }
                continue;
            }
            Matcher m = STEP.matcher(line);
            if (m.matches() && !block.isEmpty()) {
                String word = m.group(1).toLowerCase(Locale.ROOT);
                if (List.of("given", "when", "then").contains(word)) {
                    section = word;
                }
                Step step = new Step(section, m.group(2).strip(), new ArrayList<>());
                (block.equals("background") ? background : steps).add(step);
            }
        }
        if (block.equals("scenario")) {
            scenarios.add(new Scenario(name, tags, inherited, first, endOf(lines, lines.length - 1), steps, examples));
        }
        return new Feature(featureTags, background, scenarios);
    }

    /** True when the table at {@code line} follows an Examples keyword (not a step's data table). */
    private static boolean examplesAfterSteps(String[] lines, int line) {
        for (int i = line - 1; i >= 0; i--) {
            String l = lines[i].strip();
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("|") || l.startsWith("@")) {
                continue;
            }
            return "examples".equals(keyword(l));
        }
        return false;
    }

    private static int endOf(String[] lines, int last) {
        int end = last;
        while (end > 0 && lines[end].isBlank()) {
            end--;
        }
        return end;
    }

    private static Optional<Step> lastStep(String block, List<Step> steps, List<Step> background) {
        List<Step> list = block.equals("background") ? background : block.equals("scenario") ? steps : null;
        return list == null || list.isEmpty() ? Optional.empty() : Optional.of(list.get(list.size() - 1));
    }

    private static List<String> cells(String line) {
        List<String> out = new ArrayList<>();
        String inner = line.strip();
        inner = inner.substring(1, inner.endsWith("|") ? inner.length() - 1 : inner.length());
        for (String c : inner.split("(?<!\\\\)\\|", -1)) {
            out.add(c.strip());
        }
        return out;
    }

    /** The block keyword a line starts with: feature, rule, background, scenario, examples; null for anything else. */
    public static String keyword(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return null;
        }
        return switch (line.substring(0, colon).strip()) {
            case "Feature" -> "feature";
            case "Rule" -> "rule";
            case "Background" -> "background";
            case "Scenario", "Example", "Scenario Outline", "Scenario Template" -> "scenario";
            case "Examples", "Scenarios" -> "examples";
            default -> null;
        };
    }

    /** The feature files under the test resources that hold a scenario tagged {@code @scenario:<id>}. */
    public static List<Path> find(Path testResources, String scenarioId) {
        List<Path> out = new ArrayList<>();
        for (Path f : featureFiles(testResources)) {
            if (read(f).contains("@scenario:" + scenarioId)) {
                out.add(f);
            }
        }
        return out;
    }

    static List<Path> featureFiles(Path testResources) {
        if (testResources == null || !Files.isDirectory(testResources)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(testResources)) {
            return files.filter(f -> f.toString().endsWith(".feature")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * The oracle of the scenarios tagged {@code @scenario:<id>}: their Then steps are the assertion statements, the
     * values in them (quoted text, numbers, constants, doc strings, table cells, an outline's examples) the tokens.
     */
    public static AssertionScanner.ScenarioOracle scan(Path testResources, String scenarioId) {
        String tag = "scenario:" + scenarioId;
        int scenarios = 0;
        List<String> statements = new ArrayList<>();
        Set<String> tokens = new LinkedHashSet<>();
        StringBuilder watched = new StringBuilder();
        for (Path f : find(testResources, scenarioId)) {
            Feature feature = parse(read(f));
            for (Scenario s : feature.scenarios()) {
                if (!s.tags().contains(tag)) {
                    continue;
                }
                scenarios++;
                feature.background().forEach(step -> watched.append(normalize(step)));
                for (Step step : s.steps()) {
                    if (step.section().equals("then")) {
                        statements.add("then:" + normalize(step));
                        tokens(step.text(), tokens);
                        step.arguments().forEach(a -> {
                            tokens.add(a);
                            List.of(a.split("\\|")).forEach(cell -> tokens(cell, tokens));
                        });
                    } else {
                        watched.append(normalize(step));
                    }
                    fixtures(step, testResources, watched);
                }
                // the Then steps of an outline hold <placeholders>: the columns they name carry the expected values,
                // the other columns are inputs (plan §5.3.2)
                String then = s.steps().stream()
                        .filter(step -> step.section().equals("then"))
                        .map(FeatureScanner::normalize)
                        .reduce("", String::concat);
                List<String> expected = new ArrayList<>();
                for (String cell : s.examples()) {
                    String column = cell.substring(0, cell.indexOf('='));
                    if (then.contains("<" + column.replaceAll("\\s+", "") + ">")) {
                        tokens(cell.substring(column.length() + 1), tokens);
                        expected.add(cell);
                    } else {
                        watched.append(cell);
                    }
                }
                if (!expected.isEmpty()) {
                    statements.add("examples:" + String.join("|", expected).replaceAll("\\s+", ""));
                }
            }
        }
        if (scenarios > 0) {
            // the step definitions turn the steps into calls and assertions: a change to them changes the inputs
            for (Path glue : glue(testResources.resolveSibling("java"))) {
                watched.append(Hashes.sha256(glue));
            }
        }
        return new AssertionScanner.ScenarioOracle(scenarios, statements, tokens, Hashes.shortSha(watched.toString()));
    }

    private static void tokens(String text, Set<String> tokens) {
        String t = text.strip();
        if (!t.isEmpty() && !t.contains(" ")) {
            tokens.add(t); // a table cell or a one-word value
        }
        Matcher q = QUOTED.matcher(text);
        while (q.find()) {
            tokens.add(q.group(1) != null ? q.group(1) : q.group(2));
        }
        Matcher n = NUMBER.matcher(text);
        while (n.find()) {
            String number = n.group();
            tokens.add(number);
            tokens.add(number.replace(",", "").replace("_", ""));
        }
        Matcher c = CONSTANT.matcher(text);
        while (c.find()) {
            tokens.add(c.group());
            String code = AssertionScanner.STATUS_NAMES.get(c.group());
            if (code != null) {
                tokens.add(code);
            }
        }
        Matcher b = WORD_BOOLEAN.matcher(text);
        while (b.find()) {
            tokens.add(b.group());
        }
    }

    private static void fixtures(Step step, Path testResources, StringBuilder watched) {
        Matcher m = FIXTURE.matcher(step.text());
        while (m.find()) {
            String rel = m.group().startsWith("classpath:") ? m.group().substring(10) : m.group();
            Path file = testResources.resolve(rel.startsWith("/") ? rel.substring(1) : rel);
            if (Files.isRegularFile(file)) {
                watched.append(rel).append('=').append(Hashes.sha256(file));
            }
        }
    }

    /** The test sources that define Cucumber steps or hooks. */
    static List<Path> glue(Path testJava) {
        if (testJava == null || !Files.isDirectory(testJava)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(testJava)) {
            return files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> read(f).contains("io.cucumber.java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * The feature text without the scenarios tagged {@code @scenario:<id>} (their tags, steps and examples), or empty
     * when no scenario has the tag.
     */
    public static Optional<String> withoutScenario(String text, String scenarioId) {
        Feature feature = parse(text);
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        List<Scenario> drop = feature.scenarios().stream()
                .filter(s -> s.tags().contains("scenario:" + scenarioId))
                .toList();
        if (drop.isEmpty()) {
            return Optional.empty();
        }
        for (int i = drop.size() - 1; i >= 0; i--) {
            Scenario s = drop.get(i);
            int end = s.lastLine();
            while (end + 1 < lines.size() && lines.get(end + 1).isBlank() && end + 2 < lines.size()) {
                end++; // the blank line after the scenario goes with it
            }
            lines.subList(s.firstLine(), end + 1).clear();
        }
        return Optional.of(String.join("\n", lines));
    }

    static String normalize(Step step) {
        return (step.text() + String.join("|", step.arguments())).replaceAll("\\s+", "");
    }

    static String read(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
