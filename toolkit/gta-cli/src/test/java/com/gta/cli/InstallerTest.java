package com.gta.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.gta.core.error.GtaException;
import com.gta.core.util.Hashes;
import com.gta.core.util.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallerTest {
    @TempDir
    Path root;

    @BeforeEach
    void service() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Files.writeString(root.resolve("AGENTS.md"), "# Team rules\n\nUse records.\n");
        Files.createDirectories(root.resolve(".vscode"));
    }

    @Test
    void shouldMergePlainJsonAndKeepTeamFiles() throws Exception {
        Files.writeString(
                root.resolve(".vscode/settings.json"),
                "{ \"editor.tabSize\": 4, \"files.exclude\": { \"a, }\": true } }\n");

        new Installer(root).install("copilot", null, null, false);

        String agents = Files.readString(root.resolve("AGENTS.md"));
        assertTrue(agents.startsWith("# Team rules"), agents);
        assertTrue(agents.contains("<!-- gta:begin -->") && agents.contains("/gta"), agents);
        JsonNode settings =
                Json.MAPPER.readTree(root.resolve(".vscode/settings.json").toFile());
        assertEquals(4, settings.get("editor.tabSize").asInt());
        assertTrue(settings.get("files.exclude").has("a, }"), "string values must not be rewritten");
        assertTrue(settings.get("chat.tools.terminal.autoApprove").size() > 0);
        assertTrue(Files.exists(root.resolve(".github/agents/gta-orchestrator.agent.md")));
        assertTrue(Files.readString(root.resolve(".gitignore")).contains(".gta/work/"));
    }

    @Test
    void shouldNotRewriteJsoncButReportTheKeysToAdd() throws Exception {
        String jsonc =
                """
                {
                  // team setting
                  "java.jdt.ls.java.home": "C:/jdk17", // local
                  /* block */ "editor.tabSize": 4,
                }
                """;
        Files.writeString(root.resolve(".vscode/settings.json"), jsonc);

        Map<String, Object> out = new Installer(root).install("copilot", null, null, false);

        assertEquals(jsonc, Files.readString(root.resolve(".vscode/settings.json")), "comments must survive");
        assertTrue(((Map<?, ?>) out.get("mergeManually")).containsKey(".vscode/settings.json"), out.toString());
    }

    @Test
    void shouldWriteNothingWhenAnInputCannotBeParsed() throws Exception {
        Files.writeString(root.resolve(".vscode/settings.json"), "{ \"broken\": ");

        assertThrows(GtaException.class, () -> new Installer(root).install("copilot", null, null, false));

        assertEquals("# Team rules\n\nUse records.\n", Files.readString(root.resolve("AGENTS.md")));
        assertFalse(Files.exists(root.resolve(".github/agents")));
        assertFalse(Files.exists(root.resolve(".gta/managed-files.json")));
    }

    @Test
    void shouldBeIdempotent() throws Exception {
        new Installer(root).install("copilot", null, null, false);
        String first = Files.readString(root.resolve("AGENTS.md"));
        new Installer(root).install("copilot", null, null, true);

        assertEquals(first, Files.readString(root.resolve("AGENTS.md")));
        assertEquals(1, first.split("<!-- gta:begin -->", -1).length - 1);
    }

    @Test
    void shouldKeepLocallyEditedManagedFiles() throws Exception {
        new Installer(root).install("opencode", null, null, false);
        Path agent = root.resolve(".opencode/agents/gta-test-author.md");
        Files.writeString(agent, Files.readString(agent) + "\nlocal tweak\n");

        Map<String, Object> out = new Installer(root).install("opencode", null, null, true);

        assertEquals(List.of(".opencode/agents/gta-test-author.md"), out.get("conflicts"));
        assertTrue(Files.readString(agent).contains("local tweak"));
    }

    @Test
    void shouldRemoveUntouchedFilesANewVersionNoLongerShipsAndKeepEditedOnes() throws Exception {
        new Installer(root).install("copilot", null, null, false);
        Path managed = root.resolve(".gta/managed-files.json");
        Map<String, Object> m = Json.read(managed, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, String> files = (Map<String, String>) m.get("files");
        // pretend the previous version shipped two more files
        Files.createDirectories(root.resolve(".gta/skills/gta-old-skill"));
        Files.writeString(root.resolve(".gta/skills/gta-old-skill/SKILL.md"), "old\n");
        Files.writeString(root.resolve(".github/agents/gta-old.agent.md"), "old\n");
        files.put(".gta/skills/gta-old-skill/SKILL.md", Hashes.sha256("old\n"));
        files.put(".github/agents/gta-old.agent.md", Hashes.sha256("old\n"));
        Json.writeAtomic(managed, m);
        Files.writeString(root.resolve(".github/agents/gta-old.agent.md"), "old, edited here\n");

        Map<String, Object> out = new Installer(root).install("copilot", null, null, true);

        assertFalse(Files.exists(root.resolve(".gta/skills/gta-old-skill/SKILL.md")));
        assertTrue(Files.exists(root.resolve(".github/agents/gta-old.agent.md")));
        assertEquals(List.of(".github/agents/gta-old.agent.md"), out.get("obsoleteKept"));
    }
}
