package com.gta.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
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
import java.util.Properties;

/**
 * Installs one platform folder (copilot/ or opencode/, bundled in the jar) into a service repository (plan §11.2).
 * Merges AGENTS.md, .vscode/*.json and opencode.json instead of overwriting them, and records checksums of every managed
 * file in .gta/managed-files.json so upgrades never overwrite local edits.
 */
public final class Installer {
    private static final String BEGIN = "<!-- gta:begin -->";
    private static final String END = "<!-- gta:end -->";

    private final Path root;

    public Installer(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Map<String, Object> install(String platform, String distributionUrl, String distributionSha, boolean upgrade) {
        Path managedFile = root.resolve(".gta/managed-files.json");
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
        Map<String, String> files = new LinkedHashMap<>();
        List<String> written = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        int skillFiles = 0;

        for (String rel : manifest(platform)) {
            String content = resource("gta/platform/" + platform + "/" + rel);
            switch (rel) {
                case "AGENTS.gta.md" -> {
                    mergeAgentsMd(content);
                    written.add("AGENTS.md (gta block)");
                }
                case ".vscode/settings.json", ".vscode/extensions.json", "opencode.json" -> {
                    mergeJson(root.resolve(rel), content);
                    written.add(rel + " (merged)");
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
                    write(target, content);
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
        // wrapper
        for (String w : List.of("gtaw", "gtaw.cmd")) {
            Path target = root.resolve(w);
            write(target, resource("gta/wrapper/" + w));
            target.toFile().setExecutable(true);
            files.put(w, Hashes.sha256(target));
        }
        Path ps1 = root.resolve(".gta/wrapper/gtaw-download.ps1");
        write(ps1, resource("gta/wrapper/gtaw-download.ps1"));
        files.put(".gta/wrapper/gtaw-download.ps1", Hashes.sha256(ps1));
        Path props = root.resolve(".gta/wrapper/gta-wrapper.properties");
        Properties p = new Properties();
        if (Files.exists(props)) {
            try (var in = Files.newInputStream(props)) {
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
        write(props, sb.toString());
        ensureGitignore();

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", "1.0");
        manifest.put("platform", platform);
        manifest.put("version", GtaCli.version());
        manifest.put("files", files);
        Json.writeAtomic(managedFile, manifest);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", (upgrade ? "upgraded" : "installed") + " gta " + GtaCli.version() + " for " + platform);
        out.put("written", written);
        if (!conflicts.isEmpty()) {
            out.put("conflicts", conflicts);
            out.put("hint", "These managed files were edited locally and were not overwritten; diff them against the new version");
        }
        out.put("next", List.of(
                "Set distributionUrl / distributionSha256Sum in .gta/wrapper/gta-wrapper.properties to the Nexus jar",
                "Add com.gta:gta-junit (test scope) to pom.xml, or let the first foundation item do it",
                "Optional: add CODEOWNERS for .gta/** and the gta CI workflow (docs/ci-integration.md)",
                "Commit, then developers type /gta in Copilot Chat or OpenCode"));
        return out;
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

    private void mergeAgentsMd(String block) {
        Path agents = root.resolve("AGENTS.md");
        String wrapped = BEGIN + "\n" + block.strip() + "\n" + END;
        String current = "";
        try {
            if (Files.exists(agents)) {
                current = Files.readString(agents, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String updated;
        int b = current.indexOf(BEGIN);
        int e = current.indexOf(END);
        if (b >= 0 && e > b) {
            updated = current.substring(0, b) + wrapped + current.substring(e + END.length());
        } else {
            updated = (current.isBlank() ? "# AGENTS.md\n\n" : current.stripTrailing() + "\n\n") + wrapped + "\n";
        }
        write(agents, updated);
    }

    private void mergeJson(Path target, String incoming) {
        try {
            JsonNode add = Json.MAPPER.readTree(incoming);
            JsonNode base = Files.exists(target) ? Json.MAPPER.readTree(stripComments(Files.readString(target))) : Json.MAPPER.createObjectNode();
            if (!(base instanceof ObjectNode)) {
                base = Json.MAPPER.createObjectNode();
            }
            merge((ObjectNode) base, add);
            write(target, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(base) + "\n");
        } catch (IOException e) {
            throw new GtaException(ErrorCode.CONFIG_INVALID, "Cannot merge " + target + ": " + e.getMessage(), "Fix the JSON file and re-run init");
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

    /** VS Code settings files may contain // comments; strip them before parsing. */
    static String stripComments(String json) {
        return json.replaceAll("(?m)^\\s*//.*$", "").replaceAll(",\\s*([}\\]])", "$1");
    }

    private void ensureGitignore() {
        Path gi = root.resolve(".gitignore");
        try {
            String text = Files.exists(gi) ? Files.readString(gi) : "";
            if (!text.contains(".gta/work/")) {
                write(gi, text.stripTrailing() + (text.isBlank() ? "" : "\n") + "\n# gta working state (rebuildable)\n.gta/work/\n");
            }
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

    private static void write(Path target, String content) {
        Json.writeStringAtomic(target, content);
    }
}
