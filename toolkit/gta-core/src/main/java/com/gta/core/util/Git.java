package com.gta.core.util;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Thin git wrapper. Only gtaw performs git writes; agents never do (plan §5.2). */
public final class Git {
    private final Path root;

    public Git(Path root) {
        this.root = root;
    }

    public boolean isRepo() {
        return Proc.run(root, Proc.cmd("git", "rev-parse", "--is-inside-work-tree")).ok();
    }

    public void requireRepo() {
        if (!isRepo()) {
            throw new GtaException(ErrorCode.ENV_NOT_A_GIT_REPO, root + " is not a git work tree",
                    "Run gta inside the service repository (git init if it is new)");
        }
    }

    public String head() {
        Proc.Result r = Proc.run(root, Proc.cmd("git", "rev-parse", "HEAD"));
        return r.ok() ? r.output().trim() : "0000000000000000000000000000000000000000";
    }

    /** Paths (relative, forward slashes) that differ from HEAD, including untracked, non-ignored files. */
    public Set<String> dirtyPaths() {
        Set<String> paths = new LinkedHashSet<>();
        Proc.Result r = Proc.run(root, Proc.cmd("git", "status", "--porcelain=v1", "-z", "--untracked-files=all"));
        if (!r.ok()) {
            return paths;
        }
        String[] entries = r.output().split("\0");
        for (int i = 0; i < entries.length; i++) {
            String e = entries[i];
            if (e.length() < 4) {
                continue;
            }
            String status = e.substring(0, 2);
            String path = e.substring(3);
            paths.add(path);
            if (status.charAt(0) == 'R' || status.charAt(0) == 'C') {
                i++; // skip the "from" path of renames/copies
            }
        }
        return paths;
    }

    public boolean isTracked(String path) {
        return Proc.run(root, Proc.cmd("git", "ls-files", "--error-unmatch", path)).ok();
    }

    public void restoreFromHead(String path) {
        Proc.run(root, Proc.cmd("git", "checkout", "HEAD", "--", path));
    }

    public Optional<String> show(String ref, String path) {
        Proc.Result r = Proc.run(root, Proc.cmd("git", "show", ref + ":" + path));
        return r.ok() ? Optional.of(r.output()) : Optional.empty();
    }

    public List<String> listFiles(String ref, String dir) {
        Proc.Result r = Proc.run(root, Proc.cmd("git", "ls-tree", "-r", "--name-only", ref, dir));
        List<String> out = new ArrayList<>();
        if (r.ok()) {
            for (String line : r.output().split("\\R")) {
                if (!line.isBlank()) {
                    out.add(line.trim());
                }
            }
        }
        return out;
    }

    public List<String> changedFiles(String baseRef) {
        Proc.Result r = Proc.run(root, Proc.cmd("git", "diff", "--name-only", baseRef + "...HEAD"));
        List<String> out = new ArrayList<>();
        if (r.ok()) {
            for (String line : r.output().split("\\R")) {
                if (!line.isBlank()) {
                    out.add(line.trim());
                }
            }
        }
        return out;
    }

    /** Commits exactly the given paths (never `git add -A`). Returns the new HEAD or empty when nothing changed. */
    public Optional<String> commit(List<String> paths, String message) {
        List<String> add = Proc.cmd("git", "add", "--");
        add.addAll(paths);
        Proc.Result added = Proc.run(root, add);
        if (!added.ok()) {
            throw new GtaException(ErrorCode.INTERNAL_ERROR, "git add failed: " + added.output(), "Check the paths and git status");
        }
        List<String> commit = Proc.cmd("git", "commit", "--no-verify", "-m", message, "--");
        commit.addAll(paths);
        Proc.Result r = Proc.run(root, commit);
        if (!r.ok()) {
            if (r.output().contains("nothing to commit") || r.output().contains("no changes added")) {
                return Optional.empty();
            }
            throw new GtaException(ErrorCode.INTERNAL_ERROR, "git commit failed: " + r.output(),
                    "Make sure git user.name and user.email are configured");
        }
        return Optional.of(head());
    }
}
