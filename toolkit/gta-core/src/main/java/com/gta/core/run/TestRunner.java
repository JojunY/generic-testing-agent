package com.gta.core.run;

import com.gta.core.config.GtaConfig;
import com.gta.core.config.GtaPaths;
import java.util.Set;

/**
 * Runs tests of the service under test and returns classified results. {@link MavenRunner} and {@link GradleRunner} are
 * the implementations; tests of gta itself substitute a fake so the item lifecycle can be exercised without a build.
 */
public interface TestRunner {
    /**
     * @param testClass       a class name or Surefire pattern; null runs every gta test class
     * @param scenarioTag     a JUnit tag expression (e.g. {@code scenario:order.create/s01}); null runs all methods
     * @param stabilityVariant run under the configured stability timezone and locale (qualityGates.stabilityTimezone/stabilityLocale)
     * @param randomOrder     run test methods in random order
     */
    record Options(String testClass, String scenarioTag, boolean stabilityVariant, boolean randomOrder) {}

    RunResult run(String runId, String itemId, Options options, Set<String> productionClasses, Set<String> testClasses);

    /** The runner of the service's build tool (build.tool, or the build file the module has). */
    static TestRunner forBuild(GtaPaths paths, GtaConfig config) {
        return GradleRunner.selected(paths, config) ? new GradleRunner(paths, config) : new MavenRunner(paths, config);
    }
}
