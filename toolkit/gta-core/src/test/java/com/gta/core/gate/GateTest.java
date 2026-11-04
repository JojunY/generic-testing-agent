package com.gta.core.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.config.GtaConfig;
import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.oracle.AssertionScanner;
import com.gta.core.oracle.OracleLock;
import com.gta.core.oracle.OracleShard;
import com.gta.core.plan.Planner;
import com.gta.core.run.TestOutcome;
import com.gta.core.spec.SpecParser;
import com.gta.core.spec.SpecTestData;
import com.gta.core.util.ProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Gate decisions against a real base branch in a temporary git repository. */
class GateTest {
    static final String ITEM = "order.create/s01#component-01";
    static final String TEST_CLASS = "com.acme.OrderCreateIT";
    static final String TEST_V1 =
            """
            package com.acme;
            class OrderCreateIT {
                @Test
                @Tag("scenario:order.create/s01")
                void shouldCreate() {
                    assertThat(status).isEqualTo(201);
                    assertThat(total).isEqualTo("50.00");
                }
            }
            """;

    @TempDir
    Path root;

    GtaPaths paths;

    private void git(String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.email=t@example.com", "-c", "user.name=t"));
        cmd.addAll(List.of(args));
        ProcessRunner.run(root, Duration.ofSeconds(30), Map.of(), cmd);
    }

    private String fingerprint() {
        AssertionScanner.ScenarioOracle o =
                AssertionScanner.scan(paths.testSource(TEST_CLASS), "order.create/s01", paths.testResources());
        return OracleLock.fingerprint(o.assertionStatements().stream()
                .filter(s -> s.contains("201") || s.contains("50.00"))
                .toList());
    }

    private void writeShard(String relockedBy, String reason, String relockedAt) {
        OracleShard.Entry e = new OracleShard.Entry();
        e.scenarioRevision = Planner.scenarioRevision(SpecParser.parseText(SpecTestData.CREATE_ORDER)
                .scenario("order.create/s01")
                .orElseThrow());
        e.fingerprint = fingerprint();
        e.tokens = List.of("201", "50.00");
        e.testClass = TEST_CLASS;
        e.source = "observed";
        e.relockedBy = relockedBy;
        e.reason = reason;
        e.relockedAt = relockedAt;
        OracleShard.write(paths.oracleShard("order.create"), Map.of(ITEM, e));
    }

    private Gate.Result gate() {
        TestOutcome passed = new TestOutcome();
        passed.className = TEST_CLASS;
        passed.method = "shouldCreate";
        passed.outcome = "passed";
        passed.tags = List.of("gta", "scenario:order.create/s01");
        return new Gate(paths, new GtaConfig()).evaluate("main", List.of(passed), List.of());
    }

    private boolean failedWith(Gate.Result r, ErrorCode code) {
        return r.failures.stream().anyMatch(c -> c.code.equals(code.name()) && !c.waived);
    }

    @BeforeEach
    void base() throws Exception {
        paths = new GtaPaths(root);
        Files.createDirectories(paths.spec("order.create").getParent());
        Files.writeString(
                paths.spec("order.create"),
                SpecTestData.CREATE_ORDER.replace("status=draft", "status=approved rev=R2"));
        Files.createDirectories(paths.testSource(TEST_CLASS).getParent());
        Files.writeString(paths.testSource(TEST_CLASS), TEST_V1);
        writeShard("alice@corp", "first relock", "2026-10-01T10:00:00Z");
        git("init", "-q", "-b", "main");
        git("add", "-A");
        git("commit", "-qm", "base");
        git("checkout", "-q", "-b", "feature");
    }

    @Test
    void shouldPassWhenNothingChanged() {
        Gate.Result r = gate();

        assertFalse(failedWith(r, ErrorCode.GATE_ORACLE_CHANGED_WITHOUT_SPEC), r.failures.toString());
        assertEquals(1, r.checkedScenarios);
    }

    @Test
    void shouldFailWhenALockedAssertionChangesWithoutARelock() throws Exception {
        Files.writeString(
                paths.testSource(TEST_CLASS), TEST_V1.replace("isEqualTo(\"50.00\")", "isNotEqualTo(\"50.00\")"));

        assertTrue(failedWith(gate(), ErrorCode.GATE_ORACLE_CHANGED_WITHOUT_SPEC));
    }

    @Test
    void shouldAcceptASecondRelockByTheSamePerson() throws Exception {
        Files.writeString(
                paths.testSource(TEST_CLASS), TEST_V1.replace("isEqualTo(\"50.00\")", "isNotEqualTo(\"50.00\")"));
        writeShard("alice@corp", "second relock", "2026-11-04T09:00:00Z");

        Gate.Result r = gate();

        assertFalse(failedWith(r, ErrorCode.GATE_ORACLE_CHANGED_WITHOUT_SPEC), r.failures.toString());
        assertTrue(
                r.concerns.stream().anyMatch(c -> c.code.equals(ErrorCode.GATE_RELOCKED.name())),
                r.concerns.toString());
    }

    @Test
    void shouldFailWhenATestOfTheBaseNoLongerPasses() {
        TestOutcome failed = new TestOutcome();
        failed.className = TEST_CLASS;
        failed.outcome = "failed";
        failed.tags = List.of("scenario:order.create/s01");

        Gate.Result r = new Gate(paths, new GtaConfig()).evaluate("main", List.of(failed), List.of());

        assertTrue(failedWith(r, ErrorCode.GATE_SCENARIO_REGRESSED));
        assertEquals("fail", r.decision);
    }

    @Test
    void shouldFailClosedWhenTheBaseShardIsUnreadable() throws Exception {
        git("checkout", "-q", "main");
        Files.writeString(paths.oracleShard("order.create"), "{ <<<<<<< conflict");
        git("commit", "-qam", "broken shard on base");
        git("checkout", "-q", "-b", "feature-2");
        writeShard("alice@corp", "first relock", "2026-10-01T10:00:00Z");

        assertTrue(failedWith(gate(), ErrorCode.GATE_SHARD_UNREADABLE));
    }

    @Test
    void shouldRaiseAConcernWhenASpecWithLockedScenariosIsDeleted() throws Exception {
        Files.delete(paths.spec("order.create"));

        Gate.Result r = gate();

        assertTrue(
                r.concerns.stream().anyMatch(c -> c.code.equals(ErrorCode.GATE_SCENARIOS_UNLOCKED.name())),
                r.concerns.toString());
    }

    @Test
    void shouldRefuseABaseThatDoesNotExist() {
        GtaException e = assertThrows(GtaException.class, () -> new Gate(paths, new GtaConfig())
                .evaluate("origin/no-such-branch", List.of(), List.of()));

        assertEquals(ErrorCode.ENV_GIT_FAILED, e.code(), "a missing base must not pass the gate");
    }
}
