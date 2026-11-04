package com.gta.harness;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lints this repository's core/ and checks that the committed copilot/ and opencode/ folders are up to date. */
class RepositoryHarnessTest {
    static final Path REPO = Path.of("../..").toAbsolutePath().normalize();

    @Test
    void shouldLintCleanly() {
        Linter linter = new Linter(REPO);

        assertTrue(linter.run(), String.join("\n", linter.errors()));
    }

    @Test
    void shouldHaveGeneratedFoldersInSyncWithCore() {
        Generator gen = new Generator(REPO);
        for (String platform : HarnessGen.PLATFORMS) {
            List<String> diff = gen.diff(REPO.resolve(platform), gen.render(platform));
            assertEquals(List.of(), diff, platform + "/ is stale: run scripts/generate.sh");
        }
    }

    @Test
    void shouldMapTheReviewerToReadOnlyOnBothPlatforms() {
        Generator gen = new Generator(REPO);
        Map<String, String> copilot = gen.render("copilot");
        Map<String, String> opencode = gen.render("opencode");

        assertTrue(copilot.get(".github/agents/gta-reviewer.agent.md").contains("tools: [read, search]\n"));
        String reviewer = opencode.get(".opencode/agents/gta-reviewer.md");
        assertTrue(reviewer.contains("  edit: deny\n") && reviewer.contains("  bash: deny\n"), reviewer);
    }
}
