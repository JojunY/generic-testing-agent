package com.gta.core.engine;

import com.gta.core.brief.Briefs;
import com.gta.core.config.GtaConfig;
import com.gta.core.config.GtaPaths;
import com.gta.core.registry.Registry;
import com.gta.core.state.StateStore;
import com.gta.core.util.Git;

import java.nio.file.Path;

/** Shared dependencies of one gtaw invocation, and the components that use them (wired once, no globals). */
final class EngineContext {
    final GtaPaths paths;
    final GtaConfig config;
    final Registry registry;
    final StateStore store;
    final Git git;
    final Briefs briefs;

    final SessionManager sessions;
    final HumanPrompts prompts;
    final EscalationWriter escalations;
    final SpecWorkflow specs;
    final ItemWorkflow items;
    final SessionEnd sessionEnd;

    EngineContext(Path root) {
        this.paths = new GtaPaths(root.toAbsolutePath().normalize());
        this.config = GtaConfig.load(paths);
        this.registry = Registry.loadBundled();
        this.store = new StateStore(paths);
        this.git = new Git(paths.root());
        this.briefs = new Briefs(paths);
        this.sessions = new SessionManager(this);
        this.prompts = new HumanPrompts(this);
        this.escalations = new EscalationWriter(this);
        this.specs = new SpecWorkflow(this);
        this.items = new ItemWorkflow(this);
        this.sessionEnd = new SessionEnd(this);
    }
}
