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

    public void save(WorkState state) {
        Json.writeAtomic(paths.state(), state);
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

    public void savePlan(List<PlanItem> plan) {
        Json.writeAtomic(paths.plan(), plan);
    }
}
