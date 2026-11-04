package com.gta.core.spec;

import com.gta.core.profile.RefResolver;
import com.gta.core.registry.Registry;
import com.gta.core.util.Ids;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic spec rules (plan §3.3 "spec lint"). Returns human-readable issues; empty = valid. */
public final class SpecLinter {
    private static final Set<Integer> RISK_VALUES = Set.of(1, 2, 3, 4, 6, 9);
    private static final List<String> LAYERS = List.of("unit", "slice", "component", "contract", "system");
    private static final List<String> ORACLES =
            List.of("state", "response", "message", "property", "golden", "contract");
    private static final List<String> DIAGRAMS =
            List.of("sequenceDiagram", "flowchart", "graph", "stateDiagram-v2", "stateDiagram", "erDiagram");
    private static final Set<String> API_CAPABILITIES = Set.of("rest-api", "grpc-api", "graphql-api");

    private SpecLinter() {}

    public static List<String> lint(Spec spec, String expectedFlowId, RefResolver refs, Registry registry, Path root) {
        List<String> issues = new ArrayList<>();
        String flowId = spec.flowId();
        if (flowId == null) {
            issues.add("header: missing <!-- gta: flowId=... --> comment under the title");
            return issues;
        }
        if (!Ids.FLOW_ID.matcher(flowId).matches()) {
            issues.add("header: flowId '" + flowId + "' must be <domain>.<flow-name>");
        }
        if (expectedFlowId != null && !expectedFlowId.equals(flowId)) {
            issues.add("header: flowId '" + flowId + "' does not match the file location (expected " + expectedFlowId
                    + ")");
        }
        if (registry.capabilities.stream().noneMatch(c -> c.id.equals(spec.capability()))) {
            issues.add("header: unknown capability '" + spec.capability() + "'");
        }
        if (!Spec.STATUSES.contains(spec.status())) {
            issues.add("header: status must be one of " + Spec.STATUSES);
        }
        if (!RISK_VALUES.contains(spec.risk())) {
            issues.add("header: risk must be probability(1-3) x impact(1-3), i.e. one of " + RISK_VALUES);
        }
        if (API_CAPABILITIES.contains(spec.capability()) && !spec.sections.contains("access")) {
            issues.add(
                    "access: flows with an external entry point need a '## Access' section (role x operation x ownership)");
        }
        if (spec.requirements.isEmpty()) {
            issues.add("requirements: at least one '### Requirement:' is required");
        }

        Set<String> answeredRounds = new HashSet<>();
        spec.answers.forEach(a -> a.get("round").ifPresent(answeredRounds::add));
        Set<String> affected = new HashSet<>();
        spec.questions.forEach(q -> affected.addAll(q.affects()));

        for (Spec.Requirement r : spec.requirements) {
            String where = "requirement '" + r.title + "'";
            checkId(issues, where, r.id(), flowId, 'r');
            String text = r.text();
            if (!text.contains("SHALL") && !text.contains("MUST")) {
                issues.add(where + ": use EARS wording with SHALL (ubiquitous / WHEN / IF...THEN / WHILE / WHERE)");
            }
            if (text.startsWith("IF ") && !text.contains("THEN")) {
                issues.add(where + ": an 'IF' requirement needs 'THEN' (unwanted-behaviour EARS pattern)");
            }
            if (r.scenarios.isEmpty()) {
                issues.add(where + ": needs at least one '#### Scenario:'");
            }
            for (String ref : r.refs()) {
                if (refs != null && refs.hash(ref).isEmpty()) {
                    issues.add(where + ": ref '" + ref
                            + "' does not resolve (use method:<fqcn>#<method>, bean:<fqcn>#<method> or config:<key>)");
                }
            }
            for (Spec.Scenario s : r.scenarios) {
                String sw = scenarioLabel(s);
                checkId(issues, sw, s.id(), flowId, 's');
                if (!Spec.SOURCES.contains(s.source())) {
                    issues.add(sw + ": source must be one of " + Spec.SOURCES);
                }
                if ((s.source().equals("confirmed") || s.source().equals("defaulted"))
                        && !answeredRounds.contains(s.meta.getOr("by", "-"))) {
                    issues.add(sw + ": source=" + s.source() + " can only be written by gtaw from an answer record");
                }
                if (s.source().equals("documented")) {
                    checkDocumented(issues, sw, s.meta.getOr("doc", ""), s.meta.getOr("quote", ""), root);
                }
                if (s.source().equals("assumed") && !affected.contains(s.id()) && !affected.contains(r.id())) {
                    issues.add(
                            sw + ": an assumed scenario must be covered by an open question (affects=" + s.id() + ")");
                }
                if (s.thens.isEmpty()) {
                    issues.add(sw + ": needs at least one '- **THEN** ...' clause");
                } else if (!s.reviewerChecked() && s.thenLiterals().isEmpty()) {
                    issues.add(
                            sw
                                    + ": THEN must contain at least one expected value in backticks, e.g. `503`, or mark check=reviewer");
                }
                s.meta.get("hint.layer")
                        .filter(l -> !LAYERS.contains(l))
                        .ifPresent(l -> issues.add(sw + ": hint.layer must be one of " + LAYERS));
                s.meta.get("hint.oracle")
                        .filter(o -> !ORACLES.contains(o))
                        .ifPresent(o -> issues.add(sw + ": hint.oracle must be one of " + ORACLES));
            }
        }
        for (Spec.Invariant inv : spec.invariants) {
            checkId(issues, "invariant '" + inv.text + "'", inv.id(), flowId, 'i');
            if ("assumed".equals(inv.meta.getOr("source", "observed")) && !affected.contains(inv.id())) {
                issues.add("invariant " + inv.id() + ": an assumed invariant must be covered by an open question");
            }
        }
        for (Spec.Question q : spec.questions) {
            String qw = "question " + q.number;
            checkId(issues, qw, q.id(), flowId, 'q');
            if (!q.impact().equals("high") && !q.impact().equals("normal")) {
                issues.add(qw + ": impact must be high or normal");
            }
            if (q.options.size() < 2) {
                issues.add(qw + ": needs at least two options '- [ ] A) ...'");
            }
            if (q.impact().equals("normal") && q.currentCodeOption().isEmpty()) {
                issues.add(
                        qw
                                + ": a normal-impact question needs an option marked '= current code' (picked by 'rest default')");
            }
            for (Spec.Option o : q.options) {
                if (!o.isCurrentCode() && !o.text.contains("→") && !o.text.contains("->")) {
                    issues.add(qw + " option " + o.letter
                            + ": state the effect on THEN ('→ ...') or mark '= current code'");
                }
            }
            if (q.affects().isEmpty()) {
                issues.add(qw + ": needs affects=<scenario or requirement id>");
            }
        }
        for (String block : spec.mermaidBlocks) {
            String first = block.strip().split("\\R")[0].trim();
            if (DIAGRAMS.stream().noneMatch(first::startsWith)) {
                issues.add("diagram: unsupported mermaid type '" + first + "' (use " + DIAGRAMS + ")");
            }
            if (block.split("\\R").length > 40) {
                issues.add("diagram: keep each diagram under 40 lines (only what the tests need)");
            }
        }
        return issues;
    }

    private static void checkId(List<String> issues, String where, String id, String flowId, char kind) {
        if (id == null) {
            issues.add(where + ": missing <!-- gta: id=" + flowId + "/" + kind + "NN -->");
        } else if (!Ids.SPEC_ELEMENT_ID.matcher(id).matches() || !id.startsWith(flowId + "/" + kind)) {
            issues.add(where + ": id '" + id + "' must look like " + flowId + "/" + kind + "01");
        }
    }

    /** How lint issues name a scenario; callers match issues by this prefix, never by title substring. */
    public static String scenarioLabel(Spec.Scenario s) {
        return "scenario " + (s.id() == null ? "(no id)" : s.id()) + " '" + s.title + "'";
    }

    private static void checkDocumented(List<String> issues, String where, String doc, String quote, Path root) {
        if (doc.isEmpty() || quote.isEmpty()) {
            issues.add(where + ": source=documented needs doc=<path>#<anchor> and quote=\"<verbatim text>\"");
            return;
        }
        if (root == null) {
            return;
        }
        Path file = root.resolve(doc.contains("#") ? doc.substring(0, doc.indexOf('#')) : doc);
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
            if (!content.contains(quote.replaceAll("\\s+", " "))) {
                issues.add(where + ": quote not found verbatim in " + doc + " (use source=observed instead)");
            }
        } catch (Exception e) {
            issues.add(where + ": doc " + doc + " not readable (use source=observed instead)");
        }
    }
}
