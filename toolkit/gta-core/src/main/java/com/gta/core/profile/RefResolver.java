package com.gta.core.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.gta.core.config.GtaPaths;
import com.gta.core.util.Hashes;
import com.gta.core.util.Yaml;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Resolves spec refs (plan §3.3) to content hashes:
 * {@code method:Class#m} and {@code bean:Class#m} hash the method body; {@code config:key} hashes the property value;
 * {@code file:path} hashes a file of the service module, such as a migration script.
 */
public final class RefResolver {
    private final JavaIndex main;
    private final Map<String, String> config;
    private final Path moduleRoot;

    public RefResolver(JavaIndex main, Map<String, String> config) {
        this(main, config, null);
    }

    public RefResolver(JavaIndex main, Map<String, String> config, Path moduleRoot) {
        this.main = main;
        this.config = config;
        this.moduleRoot = moduleRoot;
    }

    public static RefResolver of(Path root) {
        return new RefResolver(
                JavaIndex.of(root.resolve(GtaPaths.MAIN_JAVA)),
                loadConfig(root.resolve(GtaPaths.MAIN_RESOURCES)),
                root);
    }

    public JavaIndex mainIndex() {
        return main;
    }

    public Optional<String> hash(String ref) {
        if (ref.startsWith("method:") || ref.startsWith("bean:")) {
            return main.methodHash(ref.substring(ref.indexOf(':') + 1));
        }
        if (ref.startsWith("config:")) {
            String key = ref.substring("config:".length());
            return Optional.ofNullable(config.get(key)).map(Hashes::shortSha);
        }
        if (ref.startsWith("file:") && moduleRoot != null) {
            Path file = moduleRoot.resolve(ref.substring("file:".length())).normalize();
            if (file.startsWith(moduleRoot) && Files.isRegularFile(file)) {
                return Optional.of(Hashes.sha256(file).substring(0, 12));
            }
        }
        return Optional.empty();
    }

    public static Map<String, String> loadConfig(Path resources) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isDirectory(resources)) {
            return out;
        }
        try (Stream<Path> files = Files.list(resources)) {
            for (Path f : files.sorted().toList()) {
                String name = String.valueOf(f.getFileName()); // a listed entry always has a file name
                if (!name.startsWith("application")) {
                    continue;
                }
                parseConfig(name, Files.readString(f, StandardCharsets.UTF_8)).forEach(out::putIfAbsent);
            }
        } catch (IOException e) {
            // unreadable config is reported by lint as unresolvable refs
        }
        return out;
    }

    /** Whether a file name is a Spring Boot config file gta reads ({@code application*.properties|yml|yaml}). */
    public static boolean isConfigFile(String name) {
        return name.startsWith("application")
                && (name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml"));
    }

    /** Flattened keys of one config file's text ({@code a.b[0].c -> value}); empty for other file types. */
    public static Map<String, String> parseConfig(String name, String text) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (name.endsWith(".properties")) {
            Properties p = new Properties();
            try (Reader r = new java.io.StringReader(text)) {
                p.load(r);
            }
            p.forEach((k, v) -> out.putIfAbsent(k.toString(), v.toString()));
        } else if (name.endsWith(".yml") || name.endsWith(".yaml")) {
            for (JsonNode doc : Yaml.MAPPER
                    .readValues(Yaml.MAPPER.getFactory().createParser(text), JsonNode.class)
                    .readAll()) {
                flatten("", doc, out);
            }
        }
        return out;
    }

    private static void flatten(String prefix, JsonNode node, Map<String, String> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                flatten(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), out);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flatten(prefix + "[" + i + "]", node.get(i), out);
            }
        } else {
            out.putIfAbsent(prefix, node.asText());
        }
    }
}
