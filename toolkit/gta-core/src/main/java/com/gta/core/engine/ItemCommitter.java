package com.gta.core.engine;

import com.gta.core.change.Baseline;
import com.gta.core.change.ChangeChecker;
import com.gta.core.config.GtaPaths;
import com.gta.core.error.GtaException;
import com.gta.core.oracle.OracleLock;
import com.gta.core.oracle.OracleShard;
import com.gta.core.plan.PlanItem;
import com.gta.core.state.WorkState;
import com.gta.core.util.AtomicFiles;
import com.gta.core.util.Git;
import com.gta.core.util.Ids;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Commits a finished item together with its oracle lock, through the commit ledger (plan §5.4). */
final class ItemCommitter {
    private final EngineContext ctx;
    private final GtaPaths paths;
    private final Git git;

    ItemCommitter(EngineContext ctx) {
        this.ctx = ctx;
        this.paths = ctx.paths;
        this.git = ctx.git;
    }

    void commitItem(WorkState st, PlanItem item, WorkState.ItemState s, String runId, String summary) {
        Baseline baseline = s.baseline == null ? new ChangeChecker(paths).capture() : s.baseline;
        List<String> files = new ArrayList<>();
        List<String> mixed = new ArrayList<>();
        for (String changed : new ChangeChecker(paths).changedSince(baseline)) {
            if (!ChangeChecker.matchesAny(changed, item.ownsFiles)) {
                continue;
            }
            boolean testCode =
                    changed.startsWith(GtaPaths.TEST_JAVA + "/") || changed.startsWith(GtaPaths.TEST_RESOURCES + "/");
            if (baseline.dirty.containsKey(changed) && !testCode) {
                // e.g. pom.xml with the developer's own uncommitted edits: never commit those on their behalf.
                // Test classes are shared by the items of a flow, so earlier items' uncommitted work is gta's own.
                mixed.add(changed);
            } else {
                files.add(changed);
            }
        }
        if (!mixed.isEmpty()) {
            s.reason = "not committed because they also contain your uncommitted edits: " + mixed
                    + " — commit them yourself";
        }
        Path shardPath = paths.oracleShard(item.flowId);
        String shardBefore = readIfExists(shardPath);
        if (!item.isFoundation() && s.lock != null) {
            Map<String, OracleShard.Entry> shard = OracleShard.read(shardPath);
            OracleShard.Entry e = new OracleShard.Entry();
            e.scenarioRevision = item.scenarioRevision;
            e.fingerprint = OracleLock.fingerprint(ctx.runs.scanOracle(item).assertionStatements().stream()
                    .filter(x -> s.lock.tokens.stream().anyMatch(x::contains))
                    .toList());
            e.tokens = s.lock.tokens;
            e.testClass = item.testClass;
            e.intent = item.intent;
            e.source = item.oracle.source;
            shard.put(item.id, e);
            OracleShard.write(shardPath, shard);
            files.add(paths.rel(shardPath));
        }
        if (files.isEmpty()) {
            return;
        }
        String msg = "test(" + item.domain() + "): " + summary + "\n\nGta-Item: " + item.id + "\nGta-Run: " + runId;
        try {
            ctx.commit(files, msg).ifPresent(sha -> s.commit = sha);
        } catch (GtaException e) {
            // keep the shard and the commit together: the next submit must not see gtaw's own write as an agent change
            restore(shardPath, shardBefore);
            throw e;
        }
    }

    private static String readIfExists(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void restore(Path file, String content) {
        try {
            if (content == null) {
                Files.deleteIfExists(file);
            } else {
                AtomicFiles.write(file, content);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A pin is confirmed when gtaw committed the item's known-defect record (see EscalationWriter); the working tree does not count. */
    boolean confirmedPin(PlanItem item) {
        String rel = paths.rel(paths.escalations().resolve(Ids.itemKey(item.id) + ".md"));
        return git.show("HEAD", rel)
                .map(text -> text.contains(EscalationWriter.DEFECT_FOUND_MARKER))
                .orElse(false);
    }
}
