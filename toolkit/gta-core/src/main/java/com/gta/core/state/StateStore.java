package com.gta.core.state;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gta.core.config.GtaPaths;
import com.gta.core.plan.PlanItem;
import com.gta.core.util.Json;

import java.io.IOException;
import java.nio.file.Files;
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
            // corrupted snapshot: rebuild from specs, shards and tests (plan §5.2)
            return new WorkState();
        }
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
            return new ArrayList<>();
        }
    }

    public void savePlan(List<PlanItem> plan) {
        Json.writeAtomic(paths.plan(), plan);
    }
}
