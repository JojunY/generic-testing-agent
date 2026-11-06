package com.gta.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Lints this repository's core/, checks that the committed copilot/ and opencode/ folders are up to date, and enforces
 * the documentation language rule (English, with Chinese only in *.zh-CN.md copies and the accepted reply aliases).
 */
class RepositoryHarnessTest {
    static final Path REPO = Path.of("../..").toAbsolutePath().normalize();
    static final Pattern CJK = Pattern.compile("[\\u3000-\\u303f\\u4e00-\\u9fff\\uff00-\\uffef]+");
    /**
     * Chinese replies and markers gtaw accepts (see docs/guides/quickstart.md), the full-width punctuation of their
     * patterns, and the label of the language switch.
     */
    static final Set<String> ALLOWED = Set.of(
            "批准", "不批准", "其余默认", "其他默认", "代码现状", "简体中文", "好", "好的", "确认", "確認", "同意", "不同意", "不好", "推荐", "推薦", "（推荐：",
            "（", "）", "：");

    static final Set<String> TEXT =
            Set.of("md", "java", "yaml", "yml", "json", "txt", "sh", "cmd", "ps1", "xml", "tmpl", "properties", "py");

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

    @Test
    void shouldKeepDocumentationInEnglish() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(REPO)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String rel = REPO.relativize(f).toString().replace('\\', '/');
                String name = f.getFileName().toString();
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
                boolean skipped = rel.startsWith(".git/")
                        || rel.contains("/target/")
                        || rel.startsWith("target/")
                        || rel.startsWith("copilot/")
                        || rel.startsWith("opencode/") // generated from core/
                        || rel.contains("/src/test/") // tests of Chinese input
                        || rel.endsWith(".zh-CN.md")
                        || rel.endsWith("spec/AnswerParser.java") // the alias patterns themselves
                        || rel.endsWith("engine/Messages.java"); // the zh-CN messages of output.language (plan §11.5)
                if (skipped || !(TEXT.contains(ext) || name.equals("gtaw"))) {
                    continue;
                }
                String text;
                try {
                    text = Files.readString(f, StandardCharsets.UTF_8);
                } catch (MalformedInputException binary) {
                    continue;
                }
                Matcher m = CJK.matcher(text);
                while (m.find()) {
                    if (!ALLOWED.contains(m.group())) {
                        offenders.add(rel + ": " + m.group());
                    }
                }
            }
        }
        assertEquals(List.of(), offenders, "documentation is English; put Chinese into a *.zh-CN.md copy");
    }
}
