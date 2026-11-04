package com.gta.core.gate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gta.core.config.GtaConfig;
import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.oracle.AssertionScanner;
import com.gta.core.oracle.OracleLock;
import com.gta.core.oracle.OracleShard;
import com.gta.core.run.TestOutcome;
import com.gta.core.spec.CommentMeta;
import com.gta.core.spec.Ref;
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
import java.util.Objects;
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

        Condition() {}

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

    public Result evaluate(String baseRef, List<TestOutcome> results, List<String> unstableItems) {
        if (!git.refExists(baseRef)) {
            // comparing against nothing would make every locked scenario look new and pass the gate
            throw new GtaException(
                    ErrorCode.ENV_GIT_FAILED,
                    "The base " + baseRef + " is not a commit in this repository",
                    "Fetch it first (CI: git fetch origin " + baseRef.replaceFirst("^origin/", "")
                            + "), or pass --base <ref>");
        }
        Result r = new Result();
        r.base = baseRef;
        Map<String, Spec> currentSpecs = currentSpecs();
        Map<String, Map<String, OracleShard.Entry>> baseShards = baseShards(baseRef, r);
        Map<String, TestOutcome> byScenario = new HashMap<>();
        for (TestOutcome c : results) {
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
            Map<String, OracleShard.Entry> currentShard;
            try {
                currentShard = OracleShard.read(paths.oracleShard(flowId));
            } catch (GtaException unreadable) {
                r.failures.add(new Condition(ErrorCode.GATE_SHARD_UNREADABLE.name(), flowId, unreadable.getMessage()));
                continue;
            }
            if (spec == null || !"approved".equals(spec.status())) {
                // the locked scenarios of this flow are exempt below: make the reason visible in the PR
                r.concerns.add(new Condition(
                        ErrorCode.GATE_SCENARIOS_UNLOCKED.name(),
                        flowId,
                        spec == null
                                ? "the spec was deleted: its "
                                        + shard.getValue().size() + " locked scenario(s) are no longer checked"
                                : "the spec is " + spec.status()
                                        + ": its locked scenarios are checked again once it is approved"));
            }
            for (Map.Entry<String, OracleShard.Entry> e : shard.getValue().entrySet()) {
                String itemId = e.getKey();
                if (itemId.contains("#foundation")) {
                    continue;
                }
                r.checkedScenarios++;
                String scenarioId = itemId.substring(0, itemId.indexOf('#'));
                Optional<Spec.Scenario> scenario = spec == null ? Optional.empty() : spec.scenario(scenarioId);
                boolean changedInPr =
                        scenario.isEmpty() || !scenario.get().revision().equals(e.getValue().scenarioRevision);
                TestOutcome tc = byScenario.get(scenarioId);
                if (!changedInPr && (tc == null || !tc.passed())) {
                    r.failures.add(new Condition(
                            tc == null
                                    ? ErrorCode.GATE_SCENARIO_UNVERIFIED.name()
                                    : ErrorCode.GATE_SCENARIO_REGRESSED.name(),
                            scenarioId,
                            tc == null
                                    ? "verified on base, now missing or disabled"
                                    : "verified on base, now failing: " + tc.message));
                }
                if (!changedInPr && e.getValue().testClass != null) {
                    Path file = testFile(e.getValue().testClass);
                    AssertionScanner.ScenarioOracle now =
                            AssertionScanner.scan(file, scenarioId, paths.testResources());
                    List<String> stmts = now.assertionStatements().stream()
                            .filter(s -> e.getValue().tokens.stream().anyMatch(s::contains))
                            .toList();
                    String fp = OracleLock.fingerprint(stmts);
                    OracleShard.Entry cur = currentShard.get(itemId);
                    // a relock in this PR is a new relock record (by, reason, time) whose fingerprint matches the test
                    // now
                    OracleShard.Entry base = e.getValue();
                    boolean relocked = cur != null
                            && cur.relockedBy != null
                            && fp.equals(cur.fingerprint)
                            && !(Objects.equals(cur.relockedBy, base.relockedBy)
                                    && Objects.equals(cur.reason, base.reason)
                                    && Objects.equals(cur.relockedAt, base.relockedAt));
                    if (!fp.equals(e.getValue().fingerprint) && !relocked) {
                        r.failures.add(new Condition(
                                ErrorCode.GATE_ORACLE_CHANGED_WITHOUT_SPEC.name(),
                                scenarioId,
                                "assertions of a locked expectation changed but the spec did not"));
                    } else if (relocked) {
                        r.concerns.add(new Condition(
                                ErrorCode.GATE_RELOCKED.name(),
                                scenarioId,
                                "relocked by " + cur.relockedBy + ": " + cur.reason));
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
                String rev = s.revision();
                boolean inBase = base.values().stream().anyMatch(e -> rev.equals(e.scenarioRevision));
                boolean verifiedNow = shard.entrySet().stream()
                        .anyMatch(
                                e -> e.getKey().startsWith(s.id() + "#") && rev.equals(e.getValue().scenarioRevision));
                if (!inBase && !verifiedNow) {
                    r.failures.add(new Condition(
                            ErrorCode.GATE_SCENARIO_UNVERIFIED.name(),
                            s.id(),
                            "new or changed scenario has no verified test"));
                }
            }
        }

        // ④ new tests must be stable
        for (String item : unstableItems) {
            r.failures.add(new Condition(
                    ErrorCode.GATE_UNSTABLE_TEST.name(), item, "failed under pass^k / timezone / locale variation"));
        }

        // concerns: only about flows whose spec changed in this PR (a repo-wide ratio would show on every PR)
        int characterization = 0;
        int total = 0;
        for (Spec spec : currentSpecs.values()) {
            if (!changedSpecFlows.contains(spec.flowId())) {
                continue;
            }
            for (Spec.Scenario s : spec.scenarios()) {
                total++;
                if ("observed".equals(s.source()) || "defaulted".equals(s.source())) {
                    characterization++;
                }
                if ("assumed".equals(s.source()) || "defaulted".equals(s.source())) {
                    r.concerns.add(new Condition(
                            ("assumed".equals(s.source())
                                            ? ErrorCode.GATE_ASSUMED_CLAIM
                                            : ErrorCode.GATE_DEFAULTED_CLAIM)
                                    .name(),
                            s.id(),
                            s.source() + " claim, not confirmed by a human"));
                }
            }
            for (CommentMeta a : newAnswerRecords(spec, baseRef)) {
                r.concerns.add(new Condition(
                        ErrorCode.GATE_NEW_CONFIRMATION.name(),
                        spec.flowId(),
                        a.kind() + " " + a.getOr("rev", a.getOr("round", "")) + " via " + a.getOr("channel", "?")
                                + ": \"" + a.getOr("text", "") + "\""));
            }
        }
        if (total > 0 && (double) characterization / total > config.qualityGates.concernsCharacterizationRatio) {
            r.concerns.add(
                    new Condition(
                            ErrorCode.GATE_CHARACTERIZATION_RATIO.name(),
                            "*",
                            characterization + "/" + total
                                    + " scenarios only lock current behaviour (answer questions to turn them into correctness tests)"));
        }
        for (String unmapped : unmappedChanges(baseRef, currentSpecs)) {
            r.concerns.add(new Condition(
                    ErrorCode.GATE_UNMAPPED_CHANGE.name(), unmapped, "production change not covered by any spec ref"));
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

    private Map<String, Map<String, OracleShard.Entry>> baseShards(String baseRef, Result r) {
        Map<String, Map<String, OracleShard.Entry>> out = new HashMap<>();
        for (String file : git.listFiles(baseRef, paths.rel(paths.specs()))) {
            Optional<String> flowId =
                    file.endsWith(".oracles.json") ? GtaPaths.flowIdOfSpecPath(file) : Optional.empty();
            Optional<String> json = flowId.isPresent() ? git.show(baseRef, file) : Optional.empty();
            if (json.isEmpty()) {
                continue;
            }
            try {
                out.put(flowId.get(), OracleShard.parse(json.get(), baseRef + ":" + file));
            } catch (GtaException unreadable) {
                // fail closed: an unreadable base shard must not exempt its flow from the gate
                r.failures.add(
                        new Condition(ErrorCode.GATE_SHARD_UNREADABLE.name(), flowId.get(), unreadable.getMessage()));
            }
        }
        return out;
    }

    private Set<String> changedSpecFlows(String baseRef) {
        Set<String> out = new HashSet<>();
        List<String> changed = new ArrayList<>(git.changedFiles(baseRef));
        changed.addAll(git.dirtyPaths());
        for (String f : changed) {
            if (f.endsWith(".spec.md")) {
                GtaPaths.flowIdOfSpecPath(f).ifPresent(out::add);
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
        Stream.concat(spec.answers.stream(), spec.approvals.stream())
                .filter(a -> !before.contains(a.format()))
                .forEach(out::add);
        return out;
    }

    private List<String> unmappedChanges(String baseRef, Map<String, Spec> specs) {
        Set<String> referencedClasses = new HashSet<>();
        for (Spec s : specs.values()) {
            for (Spec.Requirement r : s.requirements) {
                for (String ref : r.refs()) {
                    Ref.parse(ref).filter(Ref::isCode).ifPresent(x -> referencedClasses.add(x.sourcePath()));
                }
            }
        }
        List<String> out = new ArrayList<>();
        if (specs.isEmpty()) {
            return out;
        }
        for (String f : git.changedFiles(baseRef)) {
            if (f.startsWith(GtaPaths.MAIN_JAVA + "/")
                    && referencedClasses.stream().noneMatch(f::endsWith)) {
                out.add(f);
            }
        }
        return out;
    }

    private void applyWaivers(Result r, String baseRef) {
        Optional<String> json = git.show(baseRef, paths.rel(paths.waivers()));
        if (json.isEmpty()) {
            return;
        }
        try {
            List<Waiver> waivers = Json.MAPPER.readValue(json.get(), new TypeReference<List<Waiver>>() {});
            LocalDate today = LocalDate.now();
            for (Condition c : r.failures) {
                for (Waiver w : waivers) {
                    boolean valid =
                            w.expires != null && !LocalDate.parse(w.expires).isBefore(today);
                    if (valid && w.code.equals(c.code) && ("*".equals(w.target) || w.target.equals(c.target))) {
                        c.waived = true;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            r.concerns.add(new Condition(
                    ErrorCode.GATE_WAIVERS_UNREADABLE.name(), paths.rel(paths.waivers()), e.getMessage()));
        }
    }

    private Path testFile(String testClass) {
        return paths.testSource(testClass);
    }
}
