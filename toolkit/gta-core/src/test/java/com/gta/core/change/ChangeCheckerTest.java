package com.gta.core.change;

import com.gta.core.config.GtaPaths;
import com.gta.core.state.WorkState;
import com.gta.core.util.Git;
import com.gta.core.util.Proc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeCheckerTest {
    @TempDir
    Path root;
    ChangeChecker checker;

    private void git(String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.email=t@example.com", "-c", "user.name=t"));
        cmd.addAll(List.of(args));
        Proc.run(root, Duration.ofSeconds(30), Map.of(), cmd);
    }

    @BeforeEach
    void repo() throws Exception {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.createDirectories(root.resolve("src/test/java"));
        Files.writeString(root.resolve("src/main/java/App.java"), "class App {}\n");
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        git("init", "-q");
        git("add", "-A");
        git("commit", "-qm", "init");
        checker = new ChangeChecker(new GtaPaths(root));
    }

    @Test
    void shouldRevertChangesOutsideTheOwnedFiles() throws Exception {
        WorkState.Baseline baseline = checker.capture();
        Files.writeString(root.resolve("src/test/java/AppIT.java"), "class AppIT {}\n");
        Files.writeString(root.resolve("src/main/java/App.java"), "class App { int hacked; }\n");

        ChangeChecker.Result r = checker.check(baseline, List.of("src/test/java/AppIT.java"), root.resolve("backup"), true);

        assertFalse(r.ok());
        assertEquals(List.of("src/main/java/App.java"), r.outOfScope());
        // git may check files out with CRLF on Windows (autocrlf); the content is what matters
        assertEquals("class App {}", Files.readString(root.resolve("src/main/java/App.java")).strip());
        assertTrue(Files.exists(root.resolve("src/test/java/AppIT.java")));
    }

    @Test
    void shouldIgnoreFilesThatWereAlreadyDirty() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><!-- local edit --></project>\n");
        WorkState.Baseline baseline = checker.capture();

        ChangeChecker.Result r = checker.check(baseline, List.of(), root.resolve("backup"), true);

        assertTrue(r.ok(), r.toString());
    }

    @Test
    void shouldReportChangesTheAgentCommittedItself() throws Exception {
        WorkState.Baseline baseline = checker.capture();
        Files.writeString(root.resolve("src/main/java/App.java"), "class App { int hacked; }\n");
        git("commit", "-qam", "make the test pass");

        ChangeChecker.Result r = checker.check(baseline, List.of("src/test/java/**"), root.resolve("backup"), true);

        assertFalse(r.ok());
        assertEquals(1, r.foreignCommits().size());
        assertEquals(List.of("src/main/java/App.java"), r.unrestorable(), "gtaw never rewrites history");
    }

    @Test
    void shouldIgnoreCommitsInGtawsLedger() throws Exception {
        WorkState.Baseline baseline = checker.capture();
        Files.writeString(root.resolve("src/test/java/AppIT.java"), "class AppIT {}\n");
        git("add", "-A");
        git("commit", "-qm", "test(app): x");
        new CommitLedger(new GtaPaths(root)).record(new Git(root).head());

        ChangeChecker.Result r = checker.check(baseline, List.of(), root.resolve("backup"), true);

        assertTrue(r.ok(), r.toString());
    }

    @Test
    void shouldNotTrustAForgedGtawTrailer() throws Exception {
        WorkState.Baseline baseline = checker.capture();
        Files.writeString(root.resolve("src/main/java/App.java"), "class App { int bent; }\n");
        git("commit", "-qam", "fix\n\nGta-Item: app.x/s01#component-01");

        ChangeChecker.Result r = checker.check(baseline, List.of("src/test/java/**"), root.resolve("backup"), true);

        assertFalse(r.ok());
        assertEquals(List.of("src/main/java/App.java"), r.unrestorable());
    }

    @Test
    void shouldSeeBothSidesOfARename() throws Exception {
        WorkState.Baseline baseline = checker.capture();
        git("mv", "src/main/java/App.java", "src/test/java/App.java");

        ChangeChecker.Result r = checker.check(baseline, List.of("src/test/java/**"), root.resolve("backup"), false);

        assertTrue(r.outOfScope().contains("src/main/java/App.java"), r.toString());
    }

    @Test
    void shouldMatchGlobs() {
        assertTrue(ChangeChecker.matchesAny("src/test/resources/fixtures/order/a.json", List.of("src/test/resources/**")));
        assertFalse(ChangeChecker.matchesAny("src/main/resources/application.yml", List.of("src/test/resources/**")));
    }
}
