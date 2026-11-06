package com.gta.core.brief;

import com.gta.core.config.GtaPaths;
import com.gta.core.plan.PlanItem;
import com.gta.core.profile.ServiceProfile;
import com.gta.core.spec.Ref;
import com.gta.core.spec.Spec;
import com.gta.core.state.WorkState;
import com.gta.core.util.AtomicFiles;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Writes self-contained briefs (plan §4.2). A brief carries everything a node needs, so it can run in a fresh
 * subagent or inline in the orchestrator (dispatch=inline).
 */
public final class BriefWriter {
    private final GtaPaths paths;
    private final String specSource;
    private final String outputLanguage;
    /** At most this many {@code submit --question} per item (plan §5.10); past it the author decides from the spec. */
    public static final int QUESTIONS_PER_ITEM = 3;

    public BriefWriter(GtaPaths paths) {
        this(paths, "gta");
    }

    /** @param specSource {@code spec.source}: gta, openspec or speckit (plan §3.6) */
    public BriefWriter(GtaPaths paths, String specSource) {
        this(paths, specSource, "en");
    }

    /** @param outputLanguage {@code output.language}: the language people read questions in (plan §5.10) */
    public BriefWriter(GtaPaths paths, String specSource, String outputLanguage) {
        this.paths = paths;
        this.specSource = specSource == null ? "gta" : specSource;
        this.outputLanguage = outputLanguage == null ? "en" : outputLanguage;
    }

    /** First clause with its keyword, the rest as AND - the way the spec writes them. */
    private static void clauses(StringBuilder b, String keyword, List<String> clauses) {
        for (int i = 0; i < clauses.size(); i++) {
            b.append("- **")
                    .append(i == 0 ? keyword : "AND")
                    .append("** ")
                    .append(clauses.get(i))
                    .append('\n');
        }
    }

    private Path file(int seq) {
        return paths.briefs().resolve(String.format("%04d.md", seq));
    }

    public Path analyze(
            int seq,
            String token,
            String flowId,
            ServiceProfile.EntryPoint entry,
            String capability,
            List<String> skills,
            List<String> lintIssues,
            List<String> answersToApply,
            boolean exists) {
        StringBuilder b = new StringBuilder();
        b.append("# gta brief ")
                .append(String.format("%04d", seq))
                .append(" — analyze `")
                .append(flowId)
                .append("`\n\n");
        b.append("- Agent: `gta-analyst`\n- Token: `").append(token).append("`\n");
        b.append("- Spec file (the only file you may change): `")
                .append(paths.rel(paths.spec(flowId)))
                .append("`\n\n");
        b.append("## Goal\n\n");
        if (!answersToApply.isEmpty()) {
            b.append(
                            "The human answered open questions. Rewrite the affected THEN clauses exactly as the chosen options say. ")
                    .append(
                            "Do not touch `source`, `status`, `rev`, ticks or the `## Answers` section — gtaw owns them.\n\n");
            answersToApply.forEach(a -> b.append("- ").append(a).append('\n'));
            b.append('\n');
        } else if (exists) {
            b.append("Fix the spec so it passes lint. Keep everything that is already correct.\n\n");
        } else {
            b.append(
                            "Read the code of this flow and write its behaviour spec. Every scenario must cite where its claim comes from ")
                    .append("(`source=observed` with a `ref`, or `documented` with `doc=` and a verbatim `quote=`). ")
                    .append(
                            "Turn anything you are not sure about, or any suspicious current behaviour, into an open question.\n\n");
        }
        b.append("## Questions\n\n")
                .append("Never guess: what the code or a document does not settle is an open question (plan §5.10).\n")
                .append(
                        "- Mark exactly one option per question `(recommended: <why>)`: the answer you would pick. It need not be")
                .append(
                        " `= current code`: when the code looks wrong and a document says otherwise, recommend the document.\n")
                .append(
                        "- Write the question so it can be answered without opening the spec: what you saw, why it matters,")
                .append(" and what each option makes the test check.\n")
                .append("- At most 5 per round, highest impact first.\n")
                .append("- Write the question and option text in ")
                .append("zh-CN".equals(outputLanguage) ? "Simplified Chinese (zh-CN)" : "English")
                .append(", the language the person reads; keep markers (`= current code`, `→`, ids) as they are.\n\n");
        appendNotes(b);
        if (!lintIssues.isEmpty()) {
            b.append("## Lint issues to fix\n\n");
            lintIssues.forEach(i -> b.append("- ").append(i).append('\n'));
            b.append('\n');
        }
        b.append("## Flow\n\n");
        if (entry != null) {
            b.append("- Entry point: `").append(entry.ref).append("`");
            if (entry.httpMethod != null) {
                b.append(" (")
                        .append(entry.httpMethod)
                        .append(' ')
                        .append(entry.path)
                        .append(")");
            }
            if (entry.trigger != null) {
                b.append(" trigger `").append(entry.trigger).append('`');
            }
            b.append("\n- Suggested risk: ").append(entry.risk).append(" (probability 1-3 × impact 1-3)\n");
        }
        b.append("- Capability: `").append(capability).append("`\n\n");
        appendExistingSpecs(b);
        appendSkills(b, skills);
        if (!exists) {
            b.append("## Template\n\nStart from this skeleton (keep the gta comments; ids follow `")
                    .append(flowId)
                    .append("/r01`, `/s01`, `/q01`, `/f01`):\n\n````markdown\n")
                    .append(template("spec.md.tmpl")
                            .replace("{{flowId}}", flowId)
                            .replace("{{capability}}", capability)
                            .replace("{{risk}}", entry == null ? "4" : String.valueOf(entry.risk))
                            .replace("{{entryRef}}", entry == null ? "method:<fqcn>#<method>" : entry.ref))
                    .append("\n````\n\n");
        }
        b.append("## When done\n\nRun `./gtaw submit --spec ")
                .append(flowId)
                .append(" --token ")
                .append(token)
                .append(
                        "` and reply with its one-line result. If it reports lint issues, fix them and submit again.\n");
        return write(seq, b.toString());
    }

    /** At most this much of {@code .gta/notes.md} goes into a brief: the newest notes, which are at the end. */
    static final int NOTES_IN_BRIEF = 6000;

    /** What people told gta ({@code /gta note}): a documented source the analyst may cite (plan §5.10). */
    private void appendNotes(StringBuilder b) {
        Path notes = paths.notes();
        if (!Files.isRegularFile(notes)) {
            return;
        }
        String text;
        try {
            text = Files.readString(notes, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (text.length() > NOTES_IN_BRIEF) {
            text = "…\n" + text.substring(text.length() - NOTES_IN_BRIEF);
        }
        b.append("## Notes from people\n\nPeople told gta the notes below. Where one settles a behaviour of this flow,")
                .append(" cite it as `source=documented doc=")
                .append(paths.rel(notes))
                .append(
                        " quote=\"<words of the note>\"` instead of asking; ignore notes about other flows.\n\n````markdown\n")
                .append(text)
                .append("\n````\n\n");
    }

    public Path author(int seq, WorkState.ItemState state, PlanItem item, Spec spec, ServiceProfile profile) {
        StringBuilder b = new StringBuilder();
        b.append("# gta brief ")
                .append(String.format("%04d", seq))
                .append(" — author `")
                .append(item.id)
                .append("`\n\n");
        b.append("- Agent: `gta-test-author`\n- Token: `").append(state.token).append("`\n");
        b.append("- Layer: `")
                .append(item.layer)
                .append("` · Intent: `")
                .append(item.intent)
                .append("` · Priority: `")
                .append(item.priority)
                .append("`\n");
        b.append("- Test class: `").append(item.testClass).append("`\n\n");

        if (item.isFoundation()) {
            b.append("## Goal\n\nWrite the seed test `")
                    .append(item.testClass)
                    .append("`: it only proves that the application ")
                    .append(
                            "context and its embedded dependencies start (no business assertions). It is the `--smoke` baseline. ")
                    .append(
                            "You may add test-scope dependencies to the build file (`pom.xml`, or `build.gradle(.kts)` for Gradle; versions managed by Spring Boot stay unversioned), ")
                    .append(
                            "shared test support classes and test resources. Make sure `com.gta:gta-junit` is a test dependency.\n\n");
        } else {
            Spec.Scenario s = spec.scenario(item.ref).orElse(null);
            if ("known-defect".equals(state.intentOverride)) {
                b.append("## Goal\n\nThe test for scenario `")
                        .append(item.ref)
                        .append("` — ")
                        .append(item.title)
                        .append(
                                " — already exists and fails because of a confirmed product defect. Pin it (see *Confirmed defect* below); ")
                        .append("do not change anything else. ");
            } else {
                b.append("## Goal\n\nWrite a `")
                        .append(item.layer)
                        .append("` test for scenario `")
                        .append(item.ref)
                        .append("` — ")
                        .append(item.title)
                        .append(". ");
            }
            b.append(
                    "characterization".equals(item.intent)
                            ? "The expectation was observed in code (not confirmed by a human): the test locks current behaviour.\n\n"
                            : "The expectation is confirmed/documented: the test checks correctness.\n\n");
            if (s != null) {
                spec.requirementOf(s)
                        .ifPresent(r ->
                                b.append("**Requirement:** ").append(r.text()).append("\n\n"));
                clauses(b, "WHEN", s.whens);
                clauses(b, "THEN", s.thens);
                b.append('\n');
            }
            b.append(
                            "## Oracle (locked)\n\nEvery value below must appear in an assertion of a test method tagged `scenario:")
                    .append(item.ref)
                    .append("`. Once the test has reached the code under test, these assertions are locked: ")
                    .append("you may add assertions, never change or remove these.\n\n");
            if (item.oracle.reviewerChecked) {
                b.append("- (checked by the reviewer — no literal values)\n");
            }
            item.oracle.then.forEach(t -> b.append("- `").append(t).append("`\n"));
            b.append('\n');
        }
        b.append("## Files you may change\n\n");
        item.ownsFiles.forEach(f -> b.append("- `").append(f).append("`\n"));
        b.append(
                "\nAnything else is reverted by `gtaw submit` (production code under `src/main` is never changed).\n\n");
        b.append("## Conventions\n\n");
        if (!item.isFoundation()) {
            b.append("- Method `should<Outcome>When<Condition>()` with `@DisplayName(\"[")
                    .append(item.ref)
                    .append("] …\")`\n");
            b.append("- Tags: `@Tag(\"")
                    .append(item.layer)
                    .append("\")`, `@Tag(\"flow:")
                    .append(item.flowId)
                    .append("\")`, `@Tag(\"scenario:")
                    .append(item.ref)
                    .append("\")`, `@Tag(\"gta\")`");
            if ("characterization".equals(item.intent)) {
                b.append(", `@Tag(\"characterization\")`");
            }
            b.append('\n');
        } else {
            b.append("- Tags: `@Tag(\"component\")`, `@Tag(\"gta\")`\n");
        }
        b.append("- Spring Boot ")
                .append(profile.bootVersion == null ? "?" : profile.bootVersion)
                .append(" · JUnit ")
                .append(profile.junitMajor)
                .append(" · ")
                .append(javaLine(profile.javaMajor))
                .append(namespaceLine(profile.bootMajor))
                .append(" · no containers (see the embedded-dependencies skill)\n");
        b.append(
                "- Never `Thread.sleep`; use Awaitility. Never `@Disabled`. Never put the actual value where the expected value goes.\n\n");
        if ("reproducing".equals(item.intent) && !"known-defect".equals(state.intentOverride)) {
            b.append("## Reproducing an incident\n\nThis test reproduces a reported incident: it asserts the expected"
                    + " behaviour the spec states and must fail on the current code, for the reason the incident"
                    + " describes. Do not change production code, and do not add `@KnownDefect`: gtaw checks the failure"
                    + " and a human confirms the defect first.\n\n");
        }
        if ("known-defect".equals(state.intentOverride)) {
            b.append(
                            "## Confirmed defect\n\nA human confirmed this failure is a product defect. Annotate the test method with ")
                    .append("`@KnownDefect(id = \"")
                    .append(item.ref)
                    .append("\", failure = \"")
                    .append(
                            state.lastError == null || state.lastError.defectFingerprint == null
                                    ? ""
                                    : state.lastError.defectFingerprint)
                    .append("\")` and submit. Do not change assertions.\n\n");
        }
        appendSkills(b, item.skills);
        b.append("## Code slice\n\n");
        Set<String> slice = new LinkedHashSet<>();
        for (Spec.Requirement r : spec.requirements) {
            if (item.isFoundation() || r.scenarios.stream().anyMatch(s -> item.ref.equals(s.id()))) {
                slice.addAll(r.refs());
            }
        }
        slice.forEach(ref ->
                b.append("- `").append(ref).append("`").append(fileHint(ref)).append('\n'));
        b.append('\n');
        List<String> siblings = siblingTests(item.testClass);
        if (!siblings.isEmpty()) {
            b.append("## Sibling tests (follow their style)\n\n");
            siblings.forEach(s -> b.append("- `").append(s).append("`\n"));
            b.append('\n');
        }
        if (state.lastError != null) {
            b.append("## Previous result\n\n- `")
                    .append(state.lastError.code)
                    .append("`: ")
                    .append(state.lastError.message == null ? "" : state.lastError.message.replace("\n", " "))
                    .append('\n');
            if (state.reason != null) {
                b.append("- Note: ").append(state.reason).append('\n');
            }
            b.append('\n');
        }
        List<WorkState.AuthorQuestion> answered =
                state.questions.stream().filter(q -> q.answer != null).toList();
        if (!answered.isEmpty()) {
            b.append("## Answers from the person\n\nYou asked; a person answered. Follow the answers; the locked oracle"
                    + " still wins over them.\n\n");
            answered.forEach(q -> b.append("- Q: ")
                    .append(q.question.replace("\n", " "))
                    .append("\n  A: ")
                    .append(q.answer.replace("\n", " "))
                    .append('\n'));
            b.append('\n');
        }
        b.append("## Commands\n\n- `./gtaw check --item ")
                .append(item.id)
                .append(" --token ")
                .append(state.token)
                .append("` — compile and run once; free, repeat as needed\n");
        b.append("- `./gtaw submit --item ")
                .append(item.id)
                .append(" --token ")
                .append(state.token)
                .append("` — final submission (counts as an attempt)\n");
        int left = QUESTIONS_PER_ITEM - state.questions.size();
        if (left > 0) {
            b.append("- `./gtaw submit --item ")
                    .append(item.id)
                    .append(" --token ")
                    .append(state.token)
                    .append(" --question \"<question>\" --recommend \"<your best answer>\"` — when the spec, the code")
                    .append(" and the answers above do not settle how to write the test, ask instead of guessing; ")
                    .append("gtaw shows it to the person and briefs you again with the answer (")
                    .append(left)
                    .append(" left; does not count as an attempt)\n");
        }
        b.append('\n');
        b.append(
                "## Return\n\nReply with the one-line result printed by `submit`. Do not run `mvn` or `git` yourself.\n");
        return write(seq, b.toString());
    }

    public Path review(int seq, WorkState.ItemState state, PlanItem item, Spec spec, String kind, String details) {
        StringBuilder b = new StringBuilder();
        b.append("# gta brief ")
                .append(String.format("%04d", seq))
                .append(" — review `")
                .append(item.id)
                .append("` (")
                .append(kind)
                .append(")\n\n- Agent: `gta-reviewer` (read-only)\n- Test class: `")
                .append(item.testClass)
                .append("`\n- Oracle source: `")
                .append(item.oracle.source)
                .append("`\n\n");
        spec.scenario(item.ref).ifPresent(s -> {
            b.append("## Scenario `").append(s.id()).append("`\n\n");
            clauses(b, "WHEN", s.whens);
            clauses(b, "THEN", s.thens);
            b.append('\n');
        });
        b.append("## What to judge\n\n").append(details).append("\n\n");
        appendSkills(
                b,
                List.of(
                        ".gta/skills/gta-reviewing-test-quality/SKILL.md",
                        ".gta/skills/gta-designing-test-oracles/SKILL.md"));
        if (kind.equals("oracle-review")) {
            b.append("## Allowed verdicts\n\n- `accept` — the test checks exactly what the THEN clauses say\n")
                    .append("- `reject` — say precisely what is wrong (weak or misplaced assertion, wrong input)\n\n");
        } else {
            b.append("## Allowed verdicts\n\n");
            b.append(
                    "- `test-defect` — the test is wrong (setup, input, mechanics); the locked expected values stay\n");
            b.append("- `flaky` — nondeterministic; only waiting/synchronisation may change\n");
            if ("catching".equals(item.intent)) {
                b.append("- `product-defect` — a catch: the change broke this behaviour (a human will confirm)\n");
            } else if ("confirmed".equals(item.oracle.source) || "documented".equals(item.oracle.source)) {
                b.append(
                        "- `product-defect` — the code violates a confirmed/documented expectation (a human will confirm)\n");
            }
            b.append("- `spec-error` — the expectation itself is wrong");
            if ("observed".equals(item.oracle.source) || "defaulted".equals(item.oracle.source)) {
                b.append(" (observed/defaulted: gtaw corrects the THEN from the actual value)");
            }
            b.append(
                    "\n- `spec-gap` — the spec misses a case needed to judge\n- `oracle-defect` — the locked assertion is mis-written (a human must unlock)\n");
            b.append(
                    "- `environment` — fails only because of the local environment\n- `expected-change` — behaviour changed on purpose\n\n");
        }
        b.append("## Return\n\nReply with exactly one line: `verdict: <verdict> — <one-sentence reason>`. ")
                .append("The orchestrator passes it to `./gtaw submit --review ")
                .append(item.id)
                .append(" --token ")
                .append(state.token)
                .append(" --verdict <verdict> --reason \"…\"`.\n");
        return write(seq, b.toString());
    }

    /**
     * With {@code spec.source=openspec|speckit} the repository's own specs are a {@code documented} source (plan §3.6):
     * the analyst cites them and never edits them.
     */
    void appendExistingSpecs(StringBuilder b) {
        List<String> found = existingSpecs(paths.root(), specSource);
        if (found.isEmpty()) {
            return;
        }
        b.append("## Existing specifications (`spec.source=")
                .append(specSource)
                .append(
                        "`)\n\nThis repository keeps its own specs. They are a documented source: when one states the behaviour of a")
                .append(
                        " scenario, cite it with `source=documented doc=<path>#<heading> quote=\"<verbatim text>\"`. Never edit")
                .append(" these files; gta writes only `.gta/specs/`.\n\n");
        found.forEach(f -> b.append("- `").append(f).append("`\n"));
        b.append('\n');
    }

    /** OpenSpec keeps {@code openspec/specs/<capability>/spec.md}; Spec Kit keeps {@code specs/<feature>/spec.md}. */
    static List<String> existingSpecs(Path root, String source) {
        Path dir = switch (source) {
            case "openspec" -> root.resolve("openspec/specs");
            case "speckit" -> root.resolve("specs");
            default -> null;
        };
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> files = Files.walk(dir, 3)) {
            return files.filter(f -> String.valueOf(f.getFileName()).equals("spec.md"))
                    .map(f -> root.relativize(f).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (java.io.IOException e) {
            return List.of();
        }
    }

    void appendSkills(StringBuilder b, List<String> skills) {
        if (skills.isEmpty()) {
            return;
        }
        b.append("## Skills to read first\n\n");
        for (String s : skills) {
            String path = s.endsWith(".md") ? s : ".gta/skills/" + s + "/SKILL.md";
            b.append("- `").append(path).append("`\n");
        }
        b.append('\n');
        List<String> lessons = com.gta.core.state.Lessons.recent(paths, 10);
        if (!lessons.isEmpty()) {
            b.append("## Lessons from earlier runs (`.gta/lessons.md`)\n\nDo not repeat these:\n\n");
            lessons.forEach(l -> b.append(l).append('\n'));
            b.append('\n');
        }
    }

    private String fileHint(String ref) {
        return Ref.parse(ref)
                .filter(Ref::isCode)
                .map(r -> paths.mainJava().resolve(r.sourcePath()))
                .filter(Files::exists)
                .map(f -> " → `" + paths.rel(f) + "`")
                .orElse("");
    }

    private List<String> siblingTests(String testClass) {
        if (testClass == null || !testClass.contains(".")) {
            return List.of();
        }
        Path dir = paths.testJava()
                .resolve(testClass.substring(0, testClass.lastIndexOf('.')).replace('.', '/'));
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .limit(5)
                    .map(paths::rel)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public static String template(String name) {
        try (InputStream in = BriefWriter.class.getResourceAsStream("/gta/templates/" + name)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private Path write(int seq, String content) {
        Path f = file(seq);
        AtomicFiles.write(f, content);
        return f;
    }

    public static String token(String sessionId, int seq) {
        return sessionId + ":" + String.format("%04d", seq);
    }

    /** The language level the test must compile at: the service's, or the floor gta supports when the pom says nothing. */
    static String javaLine(int javaMajor) {
        int level = javaMajor > 0 ? javaMajor : 17;
        return "Java " + level + " (no language features or JDK APIs newer than Java " + level + ")";
    }

    /** Spring Boot 2 predates the jakarta.* namespace; generated imports must match the service. */
    static String namespaceLine(int bootMajor) {
        return bootMajor == 2 ? " · javax.* imports (Spring Boot 2), not jakarta.*" : "";
    }
}
