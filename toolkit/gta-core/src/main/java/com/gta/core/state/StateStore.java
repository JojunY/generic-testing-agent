package com.gta.core.state;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.plan.PlanItem;
import com.gta.core.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Loads and atomically saves the working state and the derived plan. */
public final class StateStore {
    private final GtaPaths paths;

    public StateStore(GtaPaths paths) {
        this.paths = paths;
    }

    public WorkState load() {
        if (!Files.exists(paths.state())) {
            return new WorkState();
        }
        try {
            return Json.MAPPER.readValue(paths.state().toFile(), WorkState.class);
        } catch (IOException e) {
            throw corrupt(paths.state(), e);
        }
    }

    /**
     * A working-state file that cannot be read is moved aside (never overwritten) and reported. The next command finds
     * no file and rebuilds from specs, oracle shards and committed tests (plan §5.2); open human decisions are asked again.
     */
    private GtaException corrupt(Path file, IOException cause) {
        Path aside = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveFailed) {
            aside = file;
        }
        return new GtaException(ErrorCode.STATE_CORRUPT, "Cannot read " + paths.rel(file) + ": " + cause.getMessage()
                + " (kept as " + paths.rel(aside) + ")",
                "Run ./gtaw next again: gtaw rebuilds the working state from specs, oracle shards and committed tests."
                        + " Pending answers or approvals will be asked again", cause);
    }

    /** Saves the state; the session counts as active until this moment (a long Maven run is not idle time). */
    public void save(WorkState state) {
        if (state.session != null) {
            state.session.lastSeenAt = Instant.now().toString();
        }
        Json.writeAtomic(paths.state(), state);
    }

    /** Read-only load for status and reports: an unreadable file is reported but left where it is. */
    public WorkState peek() {
        if (!Files.exists(paths.state())) {
            return new WorkState();
        }
        try {
            return Json.MAPPER.readValue(paths.state().toFile(), WorkState.class);
        } catch (IOException e) {
            throw new GtaException(ErrorCode.STATE_CORRUPT, "Cannot read " + paths.rel(paths.state()) + ": " + e.getMessage(),
                    "Run ./gtaw next: it keeps a copy of the file and rebuilds the working state", e);
        }
    }

    /** Read-only plan load, see {@link #peek()}. */
    public List<PlanItem> peekPlan() {
        if (!Files.exists(paths.plan())) {
            return new ArrayList<>();
        }
        try {
            return Json.MAPPER.readValue(paths.plan().toFile(), new TypeReference<List<PlanItem>>() {
            });
        } catch (IOException e) {
            throw new GtaException(ErrorCode.STATE_CORRUPT, "Cannot read " + paths.rel(paths.plan()) + ": " + e.getMessage(),
                    "Run ./gtaw next: it keeps a copy of the file and rebuilds the working state", e);
        }
    }

    public List<PlanItem> loadPlan() {
        if (!Files.exists(paths.plan())) {
            return new ArrayList<>();
        }
        try {
            return Json.MAPPER.readValue(paths.plan().toFile(), new TypeReference<List<PlanItem>>() {
            });
        } catch (IOException e) {
            throw corrupt(paths.plan(), e);
        }
    }

    /** The plan item with this id; USAGE_UNKNOWN_ITEM if the plan does not contain it. */
    public PlanItem planItem(String id) {
        return loadPlan().stream().filter(i -> i.id.equals(id)).findFirst()
                .orElseThrow(() -> new GtaException(ErrorCode.USAGE_UNKNOWN_ITEM, "Unknown item " + id, "Run ./gtaw status"));
    }

    public void savePlan(List<PlanItem> plan) {
        Json.writeAtomic(paths.plan(), plan);
    }
}
