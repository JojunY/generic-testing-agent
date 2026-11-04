package com.gta.harness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The harness content rules of AGENTS.md (agent and skill naming, sections, sizes, references). */
class LinterTest {
    static final Path REPO = Path.of("../..").toAbsolutePath().normalize();

    @TempDir
    Path repo;

    @BeforeEach
    void copyOfTheHarnessContent() throws Exception {
        for (String dir : new String[] {"core", "platforms"}) {
            try (Stream<Path> files = Files.walk(REPO.resolve(dir))) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    Path to = repo.resolve(REPO.relativize(f));
                    Files.createDirectories(to.getParent());
                    Files.copy(f, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private String errorsAfter(String rel, String from, String to) throws Exception {
        Path f = repo.resolve(rel);
        Files.writeString(f, Files.readString(f).replace(from, to));
        Linter linter = new Linter(repo);
        assertFalse(linter.run(), "the change must be rejected");
        return String.join("\n", linter.errors());
    }

    @Test
    void shouldAcceptTheCommittedContent() {
        Linter linter = new Linter(repo);

        assertTrue(linter.run(), String.join("\n", linter.errors()));
    }

    @Test
    void shouldRejectASkillWhoseNameDoesNotMatchItsFolder() throws Exception {
        String errors =
                errorsAfter("core/skills/gta-writing-tests/SKILL.md", "name: gta-writing-tests", "name: writing-tests");

        assertTrue(errors.contains("gta-writing-tests"), errors);
    }

    @Test
    void shouldRejectASkillReferenceThatDoesNotExist() throws Exception {
        String errors = errorsAfter(
                "core/skills/gta-writing-tests/SKILL.md", "`references/naming.md`", "`references/missing.md`");

        assertTrue(errors.contains("missing.md"), errors);
    }

    @Test
    void shouldRejectAnAgentWithANonNeutralFrontmatterKey() throws Exception {
        String errors =
                errorsAfter("core/agents/gta-reviewer.md", "name: gta-reviewer", "name: gta-reviewer\ntools: [edit]");

        assertTrue(errors.contains("tools"), errors);
    }
}
