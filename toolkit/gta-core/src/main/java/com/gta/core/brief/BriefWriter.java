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

    public BriefWriter(GtaPaths paths) {
        this.paths = paths;
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
                            "You may add test-scope dependencies to `pom.xml` (versions managed by the Spring Boot parent stay unversioned), ")
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
                .append(" · no containers (see the embedded-dependencies skill)\n");
        b.append(
                "- Never `Thread.sleep`; use Awaitility. Never `@Disabled`. Never put the actual value where the expected value goes.\n\n");
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
        b.append("## Commands\n\n- `./gtaw check --item ")
                .append(item.id)
                .append(" --token ")
                .append(state.token)
                .append("` — compile and run once; free, repeat as needed\n");
        b.append("- `./gtaw submit --item ")
                .append(item.id)
                .append(" --token ")
                .append(state.token)
                .append("` — final submission (counts as an attempt)\n\n");
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
            if ("confirmed".equals(item.oracle.source) || "documented".equals(item.oracle.source)) {
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

    private void appendSkills(StringBuilder b, List<String> skills) {
        if (skills.isEmpty()) {
            return;
        }
        b.append("## Skills to read first\n\n");
        for (String s : skills) {
            String path = s.endsWith(".md") ? s : ".gta/skills/" + s + "/SKILL.md";
            b.append("- `").append(path).append("`\n");
        }
        b.append('\n');
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
        int level = javaMajor >= 17 ? javaMajor : 17;
        return "Java " + level + " (no language features or JDK APIs newer than Java " + level + ")";
    }
}
