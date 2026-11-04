package com.gta.cli;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.util.AtomicFiles;
import com.gta.core.util.Hashes;
import com.gta.core.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Installs one platform folder (copilot/ or opencode/, bundled in the jar) into a service repository (plan §11.2).
 * Merges AGENTS.md, .vscode/*.json and opencode.json instead of overwriting them, and records checksums of every managed
 * file in .gta/managed-files.json so upgrades never overwrite local edits. All inputs are read and merged before the first
 * write, and the manifest is written last.
 */
public final class Installer {
    private static final String BEGIN = "<!-- gta:begin -->";
    private static final String END = "<!-- gta:end -->";

    /** Reads VS Code style JSONC (comments, trailing commas); used only to read, never to write. */
    private static final ObjectMapper JSONC = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA).build();
    private static final ObjectWriter PRETTY = new ObjectMapper().writerWithDefaultPrettyPrinter();

    private final Path root;
    private final GtaPaths paths;

    public Installer(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.paths = new GtaPaths(this.root);
    }

    public Map<String, Object> install(String platform, String distributionUrl, String distributionSha, boolean upgrade) {
        Path managedFile = paths.managedFiles();
        Map<String, Object> managed = Files.exists(managedFile) ? Json.read(managedFile, Map.class) : new LinkedHashMap<>();
        if (platform == null) {
            platform = (String) managed.getOrDefault("platform", null);
        }
        if (!"copilot".equals(platform) && !"opencode".equals(platform)) {
            throw new GtaException(ErrorCode.USAGE_INVALID_ARGUMENT, "--platform must be copilot or opencode", "Pick the folder your team uses");
        }
        if (!Files.exists(root.resolve("pom.xml"))) {
            throw new GtaException(ErrorCode.ENV_UNSUPPORTED_BUILD, "No pom.xml in " + root, "Run init in the Maven module of the service");
        }
        @SuppressWarnings("unchecked")
        Map<String, String> previous = (Map<String, String>) managed.getOrDefault("files", new LinkedHashMap<>());

        // phase 1: compute every write; nothing touches the disk until all inputs parsed (no half-installed repo)
        Map<Path, String> writes = new LinkedHashMap<>();
        Map<String, String> files = new LinkedHashMap<>();
        List<String> written = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        Map<String, Object> mergeManually = new LinkedHashMap<>();
        int skillFiles = 0;

        for (String rel : manifest(platform)) {
            String content = resource("gta/platform/" + platform + "/" + rel);
            switch (rel) {
                case "AGENTS.gta.md" -> {
                    writes.put(root.resolve("AGENTS.md"), mergedAgentsMd(content));
                    written.add("AGENTS.md (gta block)");
                }
                case ".vscode/settings.json", ".vscode/extensions.json", "opencode.json" -> {
                    JsonMerge m = mergedJson(root.resolve(rel), content);
                    if (m.manual() != null) {
                        mergeManually.put(rel, m.manual());
                    } else if (m.content() != null) {
                        writes.put(root.resolve(rel), m.content());
                        written.add(rel + " (merged)");
                    }
                }
                default -> {
                    Path target = root.resolve(rel);
                    String newHash = Hashes.sha256(content);
                    if (Files.exists(target)) {
                        String current = Hashes.sha256(target);
                        String recorded = previous.get(rel);
                        if (!current.equals(newHash) && recorded != null && !recorded.equals(current)) {
                            conflicts.add(rel);
                            files.put(rel, recorded);
                            continue;
                        }
                    }
                    writes.put(target, content);
                    files.put(rel, newHash);
                    if (rel.startsWith(".gta/skills/")) {
                        skillFiles++;
                    } else {
                        written.add(rel);
                    }
                }
            }
        }
        if (skillFiles > 0) {
            written.add(".gta/skills/** (" + skillFiles + " files)");
        }
        // The wrapper is always replaced: it pins and verifies the gta-cli jar, so a local edit is never kept silently.
        for (String w : List.of("gtaw", "gtaw.cmd")) {
            String content = resource("gta/wrapper/" + w);
            writes.put(root.resolve(w), content);
            files.put(w, Hashes.sha256(content));
        }
        String ps1 = resource("gta/wrapper/gtaw-download.ps1");
        writes.put(paths.gta().resolve("wrapper/gtaw-download.ps1"), ps1);
        files.put(".gta/wrapper/gtaw-download.ps1", Hashes.sha256(ps1));
        writes.put(paths.wrapperProperties(), wrapperProperties(distributionUrl, distributionSha));
        gitignoreUpdate().ifPresent(text -> writes.put(root.resolve(".gitignore"), text));

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", "1.0");
        manifest.put("platform", platform);
        manifest.put("version", GtaCli.version());
        manifest.put("files", files);

        // phase 2: write; the manifest goes last so an interrupted install is detected as "not recorded" next time
        writes.forEach(AtomicFiles::write);
        root.resolve("gtaw").toFile().setExecutable(true);
        Json.writeAtomic(managedFile, manifest);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", (upgrade ? "upgraded" : "installed") + " gta " + GtaCli.version() + " for " + platform);
        out.put("written", written);
        if (!conflicts.isEmpty()) {
            out.put("conflicts", conflicts);
            out.put("hint", "These managed files were edited locally and were not overwritten; diff them against the new version");
        }
        if (!mergeManually.isEmpty()) {
            out.put("mergeManually", mergeManually);
            out.put("mergeHint", "These files contain comments, so gta did not rewrite them; add the listed keys yourself");
        }
        out.put("next", List.of(
                "Set distributionUrl / distributionSha256Sum in .gta/wrapper/gta-wrapper.properties to the Nexus jar",
                "Add com.gta:gta-junit (test scope) to pom.xml, or let the first foundation item do it",
                "Optional: add CODEOWNERS for .gta/** and the gta CI workflow (docs/ci-integration.md)",
                "Commit, then developers type /gta in Copilot Chat or OpenCode"));
        return out;
    }

    private String wrapperProperties(String distributionUrl, String distributionSha) {
        Properties p = new Properties();
        if (Files.exists(paths.wrapperProperties())) {
            try (var in = Files.newInputStream(paths.wrapperProperties())) {
                p.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        p.setProperty("version", GtaCli.version());
        if (distributionUrl != null) {
            p.setProperty("distributionUrl", distributionUrl);
        }
        if (distributionSha != null) {
            p.setProperty("distributionSha256Sum", distributionSha);
        }
        p.putIfAbsent("distributionUrl", "https://nexus.example.com/repository/maven-releases/com/gta/gta-cli/" + GtaCli.version()
                + "/gta-cli-" + GtaCli.version() + ".jar");
        StringBuilder sb = new StringBuilder("# gta wrapper: which gta-cli jar to download (from the company Nexus) and its sha256\n");
        p.stringPropertyNames().stream().sorted().forEach(k -> sb.append(k).append('=').append(p.getProperty(k)).append('\n'));
        return sb.toString();
    }

    private List<String> manifest(String platform) {
        String text = resource("gta/platform/" + platform + "/gta-manifest.txt");
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (!line.isBlank() && !line.startsWith("#")) {
                out.add(line.trim());
            }
        }
        return out;
    }

    private String mergedAgentsMd(String block) {
        Path agents = root.resolve("AGENTS.md");
        String wrapped = BEGIN + "\n" + block.strip() + "\n" + END;
        String current = read(agents).orElse("");
        int b = current.indexOf(BEGIN);
        int e = current.indexOf(END);
        if (b >= 0 && e > b) {
            return current.substring(0, b) + wrapped + current.substring(e + END.length());
        }
        return (current.isBlank() ? "# AGENTS.md\n\n" : current.stripTrailing() + "\n\n") + wrapped + "\n";
    }

    /** Result of merging gta keys into a JSON settings file: new content, nothing to do (both null), or keys to add by hand. */
    record JsonMerge(String content, JsonNode manual) {
    }

    /**
     * Deep-merges gta's keys into a JSON/JSONC settings file. Plain JSON is rewritten (only when something changes); a file
     * with comments or trailing commas is never rewritten, because that would delete the team's comments.
     */
    JsonMerge mergedJson(Path target, String incoming) {
        try {
            JsonNode add = Json.MAPPER.readTree(incoming);
            Optional<String> text = read(target);
            if (text.isEmpty() || text.get().isBlank()) {
                return new JsonMerge(PRETTY.writeValueAsString(add) + "\n", null);
            }
            JsonNode base;
            boolean plainJson;
            try {
                base = Json.MAPPER.readTree(text.get());
                plainJson = true;
            } catch (IOException notPlain) {
                base = JSONC.readTree(text.get());
                plainJson = false;
            }
            if (!(base instanceof ObjectNode baseObject)) {
                throw new GtaException(ErrorCode.CONFIG_INVALID, target + " is not a JSON object", "Fix the file and re-run init");
            }
            ObjectNode merged = baseObject.deepCopy();
            merge(merged, add);
            if (merged.equals(baseObject)) {
                return new JsonMerge(null, null);
            }
            return plainJson ? new JsonMerge(PRETTY.writeValueAsString(merged) + "\n", null) : new JsonMerge(null, add);
        } catch (IOException e) {
            throw new GtaException(ErrorCode.CONFIG_INVALID, "Cannot merge " + target + ": " + e.getMessage(),
                    "Fix the JSON file and re-run init; nothing was written");
        }
    }

    static void merge(ObjectNode base, JsonNode add) {
        Iterator<Map.Entry<String, JsonNode>> it = add.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode existing = base.get(e.getKey());
            if (existing instanceof ObjectNode o && e.getValue().isObject()) {
                merge(o, e.getValue());
            } else if (existing instanceof ArrayNode a && e.getValue().isArray()) {
                for (JsonNode v : e.getValue()) {
                    boolean present = false;
                    for (JsonNode x : a) {
                        present |= x.equals(v);
                    }
                    if (!present) {
                        a.add(v);
                    }
                }
            } else {
                base.set(e.getKey(), e.getValue());
            }
        }
    }

    private Optional<String> gitignoreUpdate() {
        String text = read(root.resolve(".gitignore")).orElse("");
        if (text.lines().anyMatch(l -> l.strip().equals(".gta/work/") || l.strip().equals(".gta/work"))) {
            return Optional.empty();
        }
        return Optional.of(text.stripTrailing() + (text.isBlank() ? "" : "\n") + "\n# gta working state (rebuildable)\n.gta/work/\n");
    }

    private static Optional<String> read(Path file) {
        try {
            return Files.exists(file) ? Optional.of(Files.readString(file, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String resource(String path) {
        try (InputStream in = Installer.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new GtaException(ErrorCode.INTERNAL_ERROR, "Missing bundled resource " + path, "Rebuild gta-cli after generating copilot/ and opencode/");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
