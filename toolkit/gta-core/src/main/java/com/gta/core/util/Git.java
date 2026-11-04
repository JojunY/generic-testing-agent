package com.gta.core.util;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Thin git wrapper. Only gtaw performs git writes; agents never do (plan §5.2).
 *
 * <p>Every call runs with a fixed locale, so output never depends on the developer's language. A query whose answer
 * the scope check or the gate relies on fails closed: when git itself fails, it throws {@code ENV_GIT_FAILED} instead
 * of returning "nothing changed".
 */
public final class Git {
    /** HEAD of a repository without commits (an unborn branch). */
    public static final String NO_COMMIT = "0000000000000000000000000000000000000000";

    private static final Map<String, String> ENV = Map.of("LC_ALL", "C", "LANG", "C", "LANGUAGE", "C");
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private final Path root;

    public Git(Path root) {
        this.root = root;
    }

    public boolean isRepo() {
        return git("rev-parse", "--is-inside-work-tree").ok();
    }

    public void requireRepo() {
        if (!isRepo()) {
            throw new GtaException(
                    ErrorCode.ENV_NOT_A_GIT_REPO,
                    root + " is not a git work tree",
                    "Run gta inside the service repository (git init if it is new)");
        }
    }

    /** The current commit, or {@link #NO_COMMIT} when the branch has no commit yet. */
    public String head() {
        ProcessRunner.Result r = git("rev-parse", "--verify", "-q", "HEAD");
        if (r.ok()) {
            return r.output().trim();
        }
        if (r.output().isBlank()) {
            return NO_COMMIT; // --verify -q prints nothing when HEAD does not exist yet
        }
        throw failed("rev-parse HEAD", r);
    }

    /** Whether {@code ref} names a commit in this repository. */
    public boolean refExists(String ref) {
        return git("rev-parse", "--verify", "-q", ref + "^{commit}").ok();
    }

    /** Paths (relative, forward slashes) that differ from HEAD, including untracked, non-ignored files. */
    public Set<String> dirtyPaths() {
        ProcessRunner.Result r = git("status", "--porcelain=v1", "-z", "--untracked-files=all");
        if (!r.ok()) {
            throw failed("status", r);
        }
        Set<String> paths = new LinkedHashSet<>();
        String[] entries = r.output().split("\0");
        for (int i = 0; i < entries.length; i++) {
            String e = entries[i];
            if (e.length() < 4) {
                continue;
            }
            char status = e.charAt(0);
            paths.add(e.substring(3));
            if ((status == 'R' || status == 'C') && i + 1 < entries.length) {
                i++;
                if (status == 'R') {
                    paths.add(entries[i]); // the source of a rename disappeared: it is a change too
                }
            }
        }
        return paths;
    }

    /** A commit between two revisions and the files it touched (against its first parent). */
    public record Commit(String sha, List<String> files) {}

    /**
     * Commits reachable from HEAD but not from {@code since}, oldest first, in one git call. When {@code since} is
     * {@link #NO_COMMIT}, every commit counts. A {@code since} that no longer exists (history rewritten) fails closed.
     */
    public List<Commit> commitsSince(String since) {
        List<Commit> out = new ArrayList<>();
        String head = head();
        if (head.equals(NO_COMMIT) || head.equals(since)) {
            return out;
        }
        String range;
        if (since == null || since.equals(NO_COMMIT)) {
            range = "HEAD";
        } else if (refExists(since)) {
            range = since + "..HEAD";
        } else {
            throw new GtaException(
                    ErrorCode.ENV_GIT_FAILED,
                    "The baseline commit " + since + " no longer exists",
                    "History was rewritten since the brief. Run ./gtaw next --takeover to restart the item from the current HEAD");
        }
        ProcessRunner.Result log =
                git("log", "--reverse", "-m", "--first-parent", "--name-only", "--format=%x1e%H", range);
        if (!log.ok()) {
            throw failed("log", log);
        }
        for (String rec : log.output().split("\u001e")) {
            List<String> lines =
                    rec.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
            if (!lines.isEmpty()) {
                out.add(new Commit(
                        lines.get(0),
                        lines.subList(1, lines.size()).stream().distinct().toList()));
            }
        }
        return out;
    }

    /** Tracked in the index (which includes files that were only staged). */
    public boolean isTracked(String path) {
        return git("ls-files", "--error-unmatch", "--", path).ok();
    }

    /** Whether the file exists in the HEAD commit. */
    public boolean inHead(String path) {
        return git("cat-file", "-e", "HEAD:" + path).ok();
    }

    /** Puts a file back to its HEAD content (index and work tree). */
    public void restoreFromHead(String path) {
        ProcessRunner.Result r = git("checkout", "HEAD", "--", path);
        if (!r.ok()) {
            throw failed("checkout HEAD -- " + path, r);
        }
    }

    /** Removes a file from the index only (the work tree file is left alone). */
    public void unstage(String path) {
        ProcessRunner.Result r = git("rm", "--cached", "-q", "--", path);
        if (!r.ok()) {
            throw failed("rm --cached " + path, r);
        }
    }

    public Optional<String> show(String ref, String path) {
        ProcessRunner.Result r = git("show", ref + ":" + path);
        return r.ok() ? Optional.of(r.output()) : Optional.empty();
    }

    public List<String> listFiles(String ref, String dir) {
        ProcessRunner.Result r = git("ls-tree", "-r", "--name-only", ref, "--", dir);
        if (!r.ok()) {
            throw failed("ls-tree " + ref, r);
        }
        return lines(r.output());
    }

    public List<String> changedFiles(String baseRef) {
        ProcessRunner.Result r = git("diff", "--name-only", baseRef + "...HEAD");
        if (!r.ok()) {
            throw failed("diff " + baseRef + "...HEAD", r);
        }
        return lines(r.output());
    }

    /**
     * Commits exactly the given paths (never {@code git add -A}). Returns the new HEAD, or empty when nothing changed.
     * When the commit fails, the paths are unstaged again, so the index is as it was before.
     */
    public Optional<String> commit(List<String> paths, String message) {
        List<String> add = ProcessRunner.cmd("git", "add", "--");
        add.addAll(paths);
        ProcessRunner.Result added = ProcessRunner.run(root, TIMEOUT, ENV, add);
        if (!added.ok()) {
            throw failed("add", added);
        }
        List<String> staged = ProcessRunner.cmd("git", "diff", "--cached", "--quiet", "--");
        staged.addAll(paths);
        if (ProcessRunner.run(root, TIMEOUT, ENV, staged).ok()) {
            return Optional.empty(); // exit 0: nothing staged for these paths
        }
        List<String> commit = ProcessRunner.cmd("git", "commit", "--no-verify", "-m", message, "--");
        commit.addAll(paths);
        ProcessRunner.Result r = ProcessRunner.run(root, TIMEOUT, ENV, commit);
        if (!r.ok()) {
            List<String> reset = ProcessRunner.cmd("git", "reset", "-q", "--");
            reset.addAll(paths);
            ProcessRunner.run(root, TIMEOUT, ENV, reset);
            throw new GtaException(
                    ErrorCode.ENV_GIT_FAILED,
                    "git commit failed: " + firstLine(r.output()),
                    "Fix the git setup (user.name and user.email; commit signing must work without a prompt), then submit again");
        }
        return Optional.of(head());
    }

    private ProcessRunner.Result git(String... args) {
        List<String> cmd = ProcessRunner.cmd("git");
        cmd.addAll(List.of(args));
        return ProcessRunner.run(root, TIMEOUT, ENV, cmd);
    }

    private GtaException failed(String what, ProcessRunner.Result r) {
        return new GtaException(
                ErrorCode.ENV_GIT_FAILED,
                "git " + what + " failed: " + firstLine(r.output()),
                "Run the same git command in " + root + " to see why (a stale .git/index.lock, or 'dubious ownership':"
                        + " git config --global --add safe.directory <path>)");
    }

    private static String firstLine(String s) {
        return s.strip().lines().findFirst().orElse("(no output)");
    }

    private static List<String> lines(String output) {
        List<String> out = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (!line.isBlank()) {
                out.add(line.trim());
            }
        }
        return out;
    }
}
