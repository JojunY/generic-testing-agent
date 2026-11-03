package com.gta.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.gta.core.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallerTest {
    @TempDir
    Path root;

    @BeforeEach
    void service() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Files.writeString(root.resolve("AGENTS.md"), "# Team rules\n\nUse records.\n");
        Files.createDirectories(root.resolve(".vscode"));
        Files.writeString(root.resolve(".vscode/settings.json"), """
                {
                  // team setting
                  "editor.tabSize": 4
                }
                """);
    }

    @Test
    void shouldMergeInsteadOfOverwritingTeamFiles() throws Exception {
        new Installer(root).install("copilot", null, null, false);

        String agents = Files.readString(root.resolve("AGENTS.md"));
        assertTrue(agents.startsWith("# Team rules"), agents);
        assertTrue(agents.contains("<!-- gta:begin -->") && agents.contains("/gta"), agents);
        JsonNode settings = Json.MAPPER.readTree(root.resolve(".vscode/settings.json").toFile());
        assertEquals(4, settings.get("editor.tabSize").asInt());
        assertTrue(settings.get("chat.tools.terminal.autoApprove").size() > 0);
        assertTrue(Files.exists(root.resolve(".github/agents/gta-orchestrator.agent.md")));
        assertTrue(Files.readString(root.resolve(".gitignore")).contains(".gta/work/"));
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
}
