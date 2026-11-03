package com.gta.core.gate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gta.core.config.GtaConfig;
import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.oracle.AssertionScanner;
import com.gta.core.oracle.OracleLock;
import com.gta.core.oracle.OracleShard;
import com.gta.core.plan.Planner;
import com.gta.core.run.TestCase;
import com.gta.core.spec.CommentMeta;
import com.gta.core.spec.Spec;
import com.gta.core.spec.SpecParser;
import com.gta.core.util.Git;
import com.gta.core.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Quality gate anchored on the base branch (plan §10.5). Runs locally at session end and (optionally) in CI.
 * No LLM is involved.
 */
public final class Gate {
    public static class Condition {
        public String code;
        public String target;
        public String message;
        public boolean waived;

        Condition() {
        }

        Condition(String code, String target, String message) {
            this.code = code;
            this.target = target;
            this.message = message;
        }
    }

    public static class Waiver {
        public String code;
        public String target;
        public String reason;
        public String expires;
        public String by;
    }

    public static class Result {
        public String schemaVersion = "1.0";
        public String decision;
        public String base;
        public List<Condition> failures = new ArrayList<>();
        public List<Condition> concerns = new ArrayList<>();
        public int checkedScenarios;
    }

    private final GtaPaths paths;
    private final GtaConfig config;
    private final Git git;

    public Gate(GtaPaths paths, GtaConfig config) {
        this.paths = paths;
        this.config = config;
        this.git = new Git(paths.root());
    }

    public Result evaluate(String baseRef, List<TestCase> results, List<String> unstableItems) {
        Result r = new Result();
        r.base = baseRef;
        Map<String, Spec> currentSpecs = currentSpecs();
        Map<String, Map<String, OracleShard.Entry>> baseShards = baseShards(baseRef);
        Map<String, TestCase> byScenario = new HashMap<>();
        for (TestCase c : results) {
            if (c.scenarioId() != null) {
                byScenario.merge(c.scenarioId(), c, (a, b) -> a.passed() ? b : a);
            }
        }
        Set<String> changedSpecFlows = changedSpecFlows(baseRef);

        // ① base-verified scenarios must still pass (any source; characterization too)
        // ③ an expectation may not change without a spec change or a relock record
        for (Map.Entry<String, Map<String, OracleShard.Entry>> shard : baseShards.entrySet()) {
            String flowId = shard.getKey();
            Spec spec = currentSpecs.get(flowId);
            Map<String, OracleShard.Entry> currentShard = OracleShard.read(paths.oracleShard(flowId));
            for (Map.Entry<String, OracleShard.Entry> e : shard.getValue().entrySet()) {
                String itemId = e.getKey();
                if (itemId.contains("#foundation")) {
                    continue;
                }
                r.checkedScenarios++;
                String scenarioId = itemId.substring(0, itemId.indexOf('#'));
                Optional<Spec.Scenario> scenario = spec == null ? Optional.empty() : spec.scenario(scenarioId);
                boolean changedInPr = scenario.isEmpty()
                        || !Planner.scenarioRevision(scenario.get()).equals(e.getValue().scenarioRevision);
                TestCase tc = byScenario.get(scenarioId);
                if (!changedInPr && (tc == null || !tc.passed())) {
                    r.failures.add(new Condition(tc == null ? ErrorCode.GATE_SCENARIO_UNVERIFIED.name() : ErrorCode.GATE_SCENARIO_REGRESSED.name(),
                            scenarioId, tc == null ? "verified on base, now missing or disabled" : "verified on base, now failing: " + tc.message));
                }
                if (!changedInPr && e.getValue().testClass != null) {
                    Path file = testFile(e.getValue().testClass);
                    AssertionScanner.ScenarioOracle now = AssertionScanner.scan(file, scenarioId, paths.root().resolve("src/test/resources"));
                    List<String> stmts = now.assertionStatements().stream()
                            .filter(s -> e.getValue().tokens.stream().anyMatch(s::contains)).toList();
                    String fp = OracleLock.fingerprint(stmts);
                    OracleShard.Entry cur = currentShard.get(itemId);
                    boolean relocked = cur != null && cur.relockedBy != null
                            && !cur.relockedBy.equals(e.getValue().relockedBy) && fp.equals(cur.fingerprint);
                    if (!fp.equals(e.getValue().fingerprint) && !relocked) {
                        r.failures.add(new Condition(ErrorCode.GATE_ORACLE_CHANGED_WITHOUT_SPEC.name(), scenarioId,
                                "assertions of a locked expectation changed but the spec did not"));
                    } else if (relocked) {
                        r.concerns.add(new Condition("GATE_RELOCKED", scenarioId, "relocked by " + cur.relockedBy + ": " + cur.reason));
                    }
                }
            }
        }

        // ② scenarios new or changed in this PR need verified tests
        for (Spec spec : currentSpecs.values()) {
            if (!"approved".equals(spec.status()) || !changedSpecFlows.contains(spec.flowId())) {
                continue;
            }
            Map<String, OracleShard.Entry> shard = OracleShard.read(paths.oracleShard(spec.flowId()));
            Map<String, OracleShard.Entry> base = baseShards.getOrDefault(spec.flowId(), Map.of());
            for (Spec.Scenario s : spec.scenarios()) {
                if ("assumed".equals(s.source())) {
                    continue;
                }
                String rev = Planner.scenarioRevision(s);
                boolean inBase = base.values().stream().anyMatch(e -> rev.equals(e.scenarioRevision));
                boolean verifiedNow = shard.entrySet().stream().anyMatch(e -> e.getKey().startsWith(s.id() + "#") && rev.equals(e.getValue().scenarioRevision));
                if (!inBase && !verifiedNow) {
                    r.failures.add(new Condition(ErrorCode.GATE_SCENARIO_UNVERIFIED.name(), s.id(), "new or changed scenario has no verified test"));
                }
            }
        }

        // ④ new tests must be stable
        for (String item : unstableItems) {
            r.failures.add(new Condition(ErrorCode.GATE_UNSTABLE_TEST.name(), item, "failed under pass^k / timezone / locale variation"));
        }

        // concerns
        int characterization = 0;
        int total = 0;
        for (Spec spec : currentSpecs.values()) {
            for (Spec.Scenario s : spec.scenarios()) {
                total++;
                if ("observed".equals(s.source()) || "defaulted".equals(s.source())) {
                    characterization++;
                }
                if ("assumed".equals(s.source()) || "defaulted".equals(s.source())) {
                    r.concerns.add(new Condition("GATE_" + s.source().toUpperCase() + "_CLAIM", s.id(), s.source() + " claim, not confirmed by a human"));
                }
            }
            for (CommentMeta a : newAnswerRecords(spec, baseRef)) {
                r.concerns.add(new Condition("GATE_NEW_CONFIRMATION", spec.flowId(),
                        a.kind() + " " + a.getOr("rev", a.getOr("round", "")) + " via " + a.getOr("channel", "?") + ": \"" + a.getOr("text", "") + "\""));
            }
        }
        if (total > 0 && (double) characterization / total > config.qualityGates.concernsCharacterizationRatio) {
            r.concerns.add(new Condition("GATE_CHARACTERIZATION_RATIO", "*",
                    characterization + "/" + total + " scenarios only lock current behaviour (answer questions to turn them into correctness tests)"));
        }
        for (String unmapped : unmappedChanges(baseRef, currentSpecs)) {
            r.concerns.add(new Condition("GATE_UNMAPPED_CHANGE", unmapped,
                    "production change not covered by any spec ref"));
        }

        applyWaivers(r, baseRef);
        boolean anyFail = r.failures.stream().anyMatch(c -> !c.waived);
        boolean anyWaived = r.failures.stream().anyMatch(c -> c.waived);
        r.decision = anyFail ? "fail" : anyWaived ? "waived" : !r.concerns.isEmpty() ? "concerns" : "pass";
        return r;
    }

    private Map<String, Spec> currentSpecs() {
        Map<String, Spec> out = new HashMap<>();
        if (!Files.isDirectory(paths.specs())) {
            return out;
        }
        try (Stream<Path> files = Files.walk(paths.specs())) {
            for (Path f : files.filter(p -> p.toString().endsWith(".spec.md")).toList()) {
                Spec s = SpecParser.parse(f);
                if (s.flowId() != null) {
                    out.put(s.flowId(), s);
                }
            }
        } catch (IOException e) {
            // no specs yet
        }
        return out;
    }

    private Map<String, Map<String, OracleShard.Entry>> baseShards(String baseRef) {
        Map<String, Map<String, OracleShard.Entry>> out = new HashMap<>();
        for (String file : git.listFiles(baseRef, ".gta/specs")) {
            if (!file.endsWith(".oracles.json")) {
                continue;
            }
            String[] parts = file.substring(".gta/specs/".length()).split("/");
            if (parts.length != 2) {
                continue;
            }
            String flowId = parts[0] + "." + parts[1].replace(".oracles.json", "");
            git.show(baseRef, file).ifPresent(json -> out.put(flowId, OracleShard.parse(json)));
        }
        return out;
    }

    private Set<String> changedSpecFlows(String baseRef) {
        Set<String> out = new HashSet<>();
        List<String> changed = new ArrayList<>(git.changedFiles(baseRef));
        changed.addAll(git.dirtyPaths());
        for (String f : changed) {
            if (f.startsWith(".gta/specs/") && f.endsWith(".spec.md")) {
                String[] parts = f.substring(".gta/specs/".length()).split("/");
                if (parts.length == 2) {
                    out.add(parts[0] + "." + parts[1].replace(".spec.md", ""));
                }
            }
        }
        return out;
    }

    private List<CommentMeta> newAnswerRecords(Spec spec, String baseRef) {
        String rel = paths.rel(paths.spec(spec.flowId()));
        Optional<String> base = git.show(baseRef, rel);
        Set<String> before = new HashSet<>();
        base.ifPresent(text -> {
            Spec b = SpecParser.parseText(text);
            b.answers.forEach(a -> before.add(a.format()));
            b.approvals.forEach(a -> before.add(a.format()));
        });
        List<CommentMeta> out = new ArrayList<>();
        Stream.concat(spec.answers.stream(), spec.approvals.stream()).filter(a -> !before.contains(a.format())).forEach(out::add);
        return out;
    }

    private List<String> unmappedChanges(String baseRef, Map<String, Spec> specs) {
        Set<String> referencedClasses = new HashSet<>();
        for (Spec s : specs.values()) {
            for (Spec.Requirement r : s.requirements) {
                for (String ref : r.refs()) {
                    if (ref.contains("#")) {
                        referencedClasses.add(ref.substring(ref.indexOf(':') + 1, ref.indexOf('#')).replace('.', '/') + ".java");
                    }
                }
            }
        }
        List<String> out = new ArrayList<>();
        if (specs.isEmpty()) {
            return out;
        }
        for (String f : git.changedFiles(baseRef)) {
            if (f.startsWith("src/main/java/") && referencedClasses.stream().noneMatch(f::endsWith)) {
                out.add(f);
            }
        }
        return out;
    }

    private void applyWaivers(Result r, String baseRef) {
        Optional<String> json = git.show(baseRef, ".gta/waivers.json");
        if (json.isEmpty()) {
            return;
        }
        try {
            List<Waiver> waivers = Json.MAPPER.readValue(json.get(), new TypeReference<List<Waiver>>() {
            });
            LocalDate today = LocalDate.now();
            for (Condition c : r.failures) {
                for (Waiver w : waivers) {
                    boolean valid = w.expires != null && !LocalDate.parse(w.expires).isBefore(today);
                    if (valid && w.code.equals(c.code) && ("*".equals(w.target) || w.target.equals(c.target))) {
                        c.waived = true;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            r.concerns.add(new Condition("GATE_WAIVERS_UNREADABLE", ".gta/waivers.json", e.getMessage()));
        }
    }

    private Path testFile(String testClass) {
        return paths.root().resolve("src/test/java").resolve(testClass.replace('.', '/') + ".java");
    }

}
