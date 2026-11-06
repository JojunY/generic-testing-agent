package com.gta.core.engine;

import com.gta.core.brief.BriefWriter;
import com.gta.core.change.CommitLedger;
import com.gta.core.config.GtaConfig;
import com.gta.core.config.GtaPaths;
import com.gta.core.registry.CapabilityRegistry;
import com.gta.core.run.TestRunner;
import com.gta.core.state.StateStore;
import com.gta.core.util.Git;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Shared dependencies of one gtaw invocation, and the components that use them (wired once, no globals). */
final class EngineContext {
    final GtaPaths paths;
    final GtaConfig config;
    final CapabilityRegistry registry;
    final StateStore store;
    final Git git;
    final BriefWriter briefs;
    final TestRunner runner;
    /** The runner tests of gta passed in, also used for runs in other checkouts; null = the build tool's. */
    private final TestRunner injectedRunner;

    final SessionManager sessions;
    final HumanPrompts prompts;
    final EscalationWriter escalations;
    final SpecWorkflow specs;
    final ItemWorkflow items;
    final Triage triage;
    final ItemCommitter committer;
    final PlanSync planSync;
    final CaseLedgers cases;
    final PrMode pr;
    final SessionEnd sessionEnd;
    final TestRuns runs;

    EngineContext(Path root) {
        this(root, null);
    }

    /** @param runner the test runner; null = the build tool's runner (tests of gta pass a fake) */
    EngineContext(Path root, TestRunner runner) {
        GtaPaths repository = new GtaPaths(root.toAbsolutePath().normalize());
        this.config = GtaConfig.load(repository);
        this.paths = repository.withModule(config.build.module);
        this.registry = CapabilityRegistry.loadBundled();
        this.store = new StateStore(paths);
        this.git = new Git(paths.root());
        this.briefs = new BriefWriter(paths, config.spec.source);
        this.runner = runner != null ? runner : TestRunner.forBuild(paths, config);
        this.injectedRunner = runner;
        this.runs = new TestRuns(this);
        this.sessions = new SessionManager(this);
        this.prompts = new HumanPrompts(this);
        this.escalations = new EscalationWriter(this);
        this.specs = new SpecWorkflow(this);
        this.items = new ItemWorkflow(this);
        this.triage = new Triage(this);
        this.committer = new ItemCommitter(this);
        this.planSync = new PlanSync(this);
        this.cases = new CaseLedgers(this);
        this.pr = new PrMode(this);
        this.sessionEnd = new SessionEnd(this);
    }

    /** The runner for another checkout of this repository (a base revision for catching tests). */
    TestRunner runnerFor(GtaPaths checkout) {
        return injectedRunner != null ? injectedRunner : TestRunner.forBuild(checkout, config);
    }

    /** The only way gtaw commits: the SHA goes into the ledger the scope check trusts. */
    Optional<String> commit(List<String> files, String message) {
        Optional<String> sha = git.commit(files, message);
        sha.ifPresent(new CommitLedger(paths)::record);
        return sha;
    }
}
