package com.gta.core.change;

import com.gta.core.config.GtaPaths;
import com.gta.core.error.GtaException;
import com.gta.core.state.WorkState;
import com.gta.core.util.AtomicFiles;
import com.gta.core.util.Git;
import com.gta.core.util.Hashes;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The authoritative scope check (plan §5.8): everything an agent changed since dispatch must lie inside the node's
 * allowed paths. It does not depend on IDE permissions or hooks. Out-of-scope files are backed up, then restored.
 */
public final class ChangeChecker {
    /** Paths no agent may change (plan §13, single list). */
    public static final List<String> PROTECTED = List.of("gtaw", "gtaw.cmd", "**/.gta/wrapper/**", "**/.gta/config.json",
            "**/.gta/waivers.json", "**/.gta/managed-files.json", "**/.gta/specs/**/*.oracles.json", "**/.gta/skills/**",
            ".gta/wrapper/**", ".gta/config.json", ".gta/waivers.json", ".gta/managed-files.json", ".gta/specs/*/*.oracles.json",
            ".gta/skills/**", ".github/agents/gta-*", ".github/prompts/gta*", ".vscode/settings.json", ".opencode/**",
            "opencode.json");
    /** Never considered agent changes: build output and gtaw's own working area. */
    private static final List<String> IGNORED = List.of("target/**", "**/target/**", ".gta/work/**", "**/.gta/work/**");

    /**
     * @param foreignCommits commits made since the baseline that gtaw did not make (see {@link CommitLedger});
     *                       gtaw never rewrites history, so their files are reported as out of scope and unrestorable
     */
    public record Result(List<String> changed, List<String> outOfScope, List<String> restored, List<String> unrestorable,
                         List<String> foreignCommits) {
        public boolean ok() {
            return outOfScope.isEmpty();
        }
    }

    private static final Map<String, PathMatcher> MATCHERS = new ConcurrentHashMap<>();

    private final GtaPaths paths;
    private final Git git;

    public ChangeChecker(GtaPaths paths) {
        this.paths = paths;
        this.git = new Git(paths.root());
    }

    public WorkState.Baseline capture() {
        WorkState.Baseline b = new WorkState.Baseline();
        b.head = git.head();
        for (String p : git.dirtyPaths()) {
            if (!matchesAny(p, IGNORED)) {
                b.dirty.put(p, contentHash(p));
            }
        }
        return b;
    }

    /** Files changed since the baseline (excluding pre-existing dirty files that are byte-identical). */
    public List<String> changedSince(WorkState.Baseline baseline) {
        Set<String> changed = new LinkedHashSet<>();
        Set<String> dirtyNow = git.dirtyPaths();
        for (String p : dirtyNow) {
            if (matchesAny(p, IGNORED)) {
                continue;
            }
            String before = baseline.dirty.get(p);
            if (before == null || !before.equals(contentHash(p))) {
                changed.add(p);
            }
        }
        for (String p : baseline.dirty.keySet()) {
            if (!dirtyNow.contains(p)) {
                changed.add(p); // a pre-existing change was reverted
            }
        }
        return new ArrayList<>(changed);
    }

    public Result check(WorkState.Baseline baseline, List<String> allowed, Path backupDir, boolean restore) {
        List<String> changed = changedSince(baseline);
        List<String> out = new ArrayList<>();
        for (String p : changed) {
            boolean allowedPath = matchesAny(p, allowed);
            if (!allowedPath || matchesAny(p, PROTECTED)) {
                out.add(p);
            }
        }
        List<String> restored = new ArrayList<>();
        List<String> unrestorable = new ArrayList<>();
        List<String> foreign = new ArrayList<>();
        Set<String> gtawCommits = new CommitLedger(paths).shas();
        for (Git.Commit c : git.commitsSince(baseline.head)) {
            if (gtawCommits.contains(c.sha())) {
                continue;
            }
            foreign.add(c.sha().substring(0, Math.min(12, c.sha().length())));
            for (String f : c.files()) {
                if (!matchesAny(f, IGNORED) && !out.contains(f)) {
                    out.add(f);
                    unrestorable.add(f);
                }
            }
            if (c.files().isEmpty()) {
                out.add("(commit " + c.sha() + ")");
            }
        }
        if (restore) {
            for (String p : out) {
                if (unrestorable.contains(p) || p.startsWith("(commit ")) {
                    continue;
                }
                if (baseline.dirty.containsKey(p)) {
                    unrestorable.add(p); // we never overwrite a developer's own uncommitted change
                    continue;
                }
                backup(p, backupDir);
                if (restore(p)) {
                    restored.add(p);
                } else {
                    unrestorable.add(p);
                }
            }
        }
        return new Result(changed, out, restored, unrestorable, foreign);
    }

    /** Puts one file back to HEAD: committed files are checked out, new ones (staged or not) are removed. */
    private boolean restore(String rel) {
        try {
            if (git.inHead(rel)) {
                git.restoreFromHead(rel);
            } else {
                if (git.isTracked(rel)) {
                    git.unstage(rel); // staged but never committed: checking out HEAD cannot remove it
                }
                Files.deleteIfExists(paths.root().resolve(rel));
            }
            return true;
        } catch (GtaException | IOException e) {
            return false;
        }
    }

    public void backup(String rel, Path backupDir) {
        Path src = paths.root().resolve(rel);
        if (!Files.exists(src)) {
            return;
        }
        AtomicFiles.copy(src, backupDir.resolve(rel));
    }

    private String contentHash(String rel) {
        Path f = paths.root().resolve(rel);
        return Files.isRegularFile(f) ? Hashes.sha256(f) : "<deleted>";
    }

    public static boolean matchesAny(String rel, List<String> globs) {
        Path p = Path.of(rel);
        for (String g : globs) {
            if (g.equals(rel)) {
                return true;
            }
            if (MATCHERS.computeIfAbsent(g, k -> FileSystems.getDefault().getPathMatcher("glob:" + k)).matches(p)) {
                return true;
            }
        }
        return false;
    }
}
