package com.gta.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The command line of gta-harness-gen (scripts/generate.sh, CI). */
class HarnessGenTest {
    static final String REPO = Path.of("../..").toAbsolutePath().normalize().toString();

    @Test
    void shouldLintAndCheckTheRepositoryAndRejectBadArguments() {
        assertEquals(0, HarnessGen.run(new String[] {"lint", "--repo", REPO}));
        assertEquals(
                0,
                HarnessGen.run(new String[] {"generate", "--repo", REPO, "--check"}),
                "committed folders are in sync");
        assertEquals(0, HarnessGen.run(new String[] {"generate", "--repo", REPO, "--check", "--platform", "opencode"}));
        assertEquals(0, HarnessGen.run(new String[] {"--help"}));
        assertEquals(2, HarnessGen.run(new String[] {}), "usage is an error without a command");
        assertEquals(2, HarnessGen.run(new String[] {"lint", "--bogus"}));
        assertEquals(2, HarnessGen.run(new String[] {"publish", "--repo", REPO}));
        assertEquals(2, HarnessGen.run(new String[] {"generate", "--repo", REPO, "--check", "--platform", "cursor"}));
    }
}
