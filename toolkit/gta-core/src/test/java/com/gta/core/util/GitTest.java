package com.gta.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Git queries the scope check and the gate rely on fail closed (AGENTS.md: untrusted state fails closed). */
class GitTest {
    @TempDir
    Path root;

    Git git;

    private ProcessRunner.Result run(String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return ProcessRunner.run(root, Duration.ofSeconds(30), Map.of(), cmd);
    }

    @BeforeEach
    void repo() {
        run("init", "-q");
        run("config", "user.email", "t@example.com");
        run("config", "user.name", "t");
        git = new Git(root);
    }

    private void commitFile(String name, String content) throws Exception {
        Files.writeString(root.resolve(name), content);
        run("add", name);
        run("commit", "-qm", "add " + name);
    }

    @Test
    void shouldReportNoCommitForABranchWithoutCommits() {
        assertEquals(Git.NO_COMMIT, git.head());
        assertEquals(List.of(), git.commitsSince(Git.NO_COMMIT));
    }

    @Test
    void shouldFailClosedWhenGitStatusFails(@TempDir Path notARepo) {
        GtaException e = assertThrows(GtaException.class, () -> new Git(notARepo).dirtyPaths());

        assertEquals(ErrorCode.ENV_GIT_FAILED, e.code(), "an empty result would let out-of-scope edits pass");
    }

    @Test
    void shouldFailClosedWhenHeadCannotBeRead(@TempDir Path notARepo) {
        assertThrows(GtaException.class, () -> new Git(notARepo).head());
    }

    @Test
    void shouldListCommitsSinceTheBaseline() throws Exception {
        commitFile("a.txt", "a\n");
        String baseline = git.head();
        commitFile("b.txt", "b\n");

        List<Git.Commit> commits = git.commitsSince(baseline);

        assertEquals(1, commits.size());
        assertEquals(List.of("b.txt"), commits.get(0).files());
    }

    @Test
    void shouldCountEveryCommitWhenTheBaselineHadNoCommit() throws Exception {
        commitFile("a.txt", "a\n");

        assertEquals(1, git.commitsSince(Git.NO_COMMIT).size());
    }

    @Test
    void shouldFailClosedWhenTheBaselineCommitNoLongerExists() throws Exception {
        commitFile("a.txt", "a\n");

        GtaException e =
                assertThrows(GtaException.class, () -> git.commitsSince("1234567890123456789012345678901234567890"));

        assertEquals(ErrorCode.ENV_GIT_FAILED, e.code());
        assertTrue(e.hint().contains("--takeover"), e.hint());
    }

    @Test
    void shouldReportRenamesAsBothPaths() throws Exception {
        commitFile("old.txt", "x\n");
        run("mv", "old.txt", "new.txt");

        assertEquals(java.util.Set.of("old.txt", "new.txt"), git.dirtyPaths());
    }

    @Test
    void shouldCommitOnlyTheGivenPaths() throws Exception {
        commitFile("a.txt", "a\n");
        Files.writeString(root.resolve("a.txt"), "changed\n");
        Files.writeString(root.resolve("other.txt"), "not mine\n");

        Optional<String> sha = git.commit(List.of("a.txt"), "test: change a");

        assertTrue(sha.isPresent());
        assertEquals(sha.get(), git.head());
        assertEquals(java.util.Set.of("other.txt"), git.dirtyPaths());
    }

    @Test
    void shouldReturnEmptyWhenThereIsNothingToCommit() throws Exception {
        commitFile("a.txt", "a\n");

        assertEquals(Optional.empty(), git.commit(List.of("a.txt"), "nothing"));
    }

    @Test
    void shouldUnstageThePathsWhenTheCommitFails() throws Exception {
        commitFile("a.txt", "a\n");
        // signing that cannot work makes git commit fail after git add
        run("config", "commit.gpgsign", "true");
        run("config", "gpg.format", "openpgp");
        run("config", "gpg.program", "false-gpg-that-does-not-exist");
        Files.writeString(root.resolve("a.txt"), "changed\n");

        GtaException e = assertThrows(GtaException.class, () -> git.commit(List.of("a.txt"), "test: change a"));

        assertEquals(ErrorCode.ENV_GIT_FAILED, e.code());
        assertTrue(run("diff", "--cached", "--quiet").ok(), "the index is as it was before the commit");
        assertEquals("changed", Files.readString(root.resolve("a.txt")).strip(), "the work tree keeps the change");
    }

    @Test
    void shouldTellCommittedFilesFromStagedOnes() throws Exception {
        commitFile("a.txt", "a\n");
        Files.writeString(root.resolve("staged.txt"), "s\n");
        run("add", "staged.txt");

        assertTrue(git.inHead("a.txt"));
        assertFalse(git.inHead("staged.txt"));
        assertTrue(git.isTracked("staged.txt"), "ls-files includes the index");

        git.unstage("staged.txt");
        assertFalse(git.isTracked("staged.txt"));
        assertTrue(Files.exists(root.resolve("staged.txt")));
    }

    @Test
    void shouldRestoreACommittedFile() throws Exception {
        commitFile("a.txt", "a\n");
        Files.writeString(root.resolve("a.txt"), "changed\n");

        git.restoreFromHead("a.txt");

        assertEquals("a", Files.readString(root.resolve("a.txt")).strip());
    }

    @Test
    void shouldFailWhenAFileCannotBeRestored() {
        assertThrows(GtaException.class, () -> git.restoreFromHead("missing.txt"));
    }

    @Test
    void shouldReadFilesOfARevisionAndTheChangesSinceIt() throws Exception {
        commitFile("a.txt", "a\n");
        String base = git.head();
        Files.createDirectories(root.resolve("dir"));
        commitFile("dir/b.txt", "b\n");

        assertTrue(git.refExists(base));
        assertFalse(git.refExists("no-such-branch"));
        assertEquals(Optional.of("a\n"), git.show(base, "a.txt"));
        assertEquals(Optional.empty(), git.show(base, "dir/b.txt"));
        assertEquals(List.of("dir/b.txt"), git.listFiles("HEAD", "dir"));
        assertEquals(List.of("dir/b.txt"), git.changedFiles(base));
        assertThrows(GtaException.class, () -> git.changedFiles("no-such-branch"), "an unknown base fails closed");
    }

    @Test
    void shouldRequireAWorkTree(@TempDir Path notARepo) {
        assertTrue(git.isRepo());
        GtaException e = assertThrows(GtaException.class, () -> new Git(notARepo).requireRepo());
        assertEquals(ErrorCode.ENV_NOT_A_GIT_REPO, e.code());
    }

    @org.junit.jupiter.api.Test
    void shouldReadTheNewSideLinesOfEachHunk() {
        String diff =
                "diff --git a/A.java b/A.java\n@@ -3 +3 @@\n-a\n+b\n@@ -10,0 +11,2 @@\n+c\n+d\n@@ -20,4 +21,0 @@\n";

        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.Set.of(3, 11, 12), Git.hunkLines(diff), "a pure deletion adds no new line");
    }
}
