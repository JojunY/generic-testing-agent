package com.gta.core.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.gta.core.util.Hashes;
import com.gta.core.util.Json;

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
 * {@code method:Class#m} and {@code bean:Class#m} hash the method body; {@code config:key} hashes the property value.
 */
public final class RefResolver {
    private final JavaIndex main;
    private final Map<String, String> config;

    public RefResolver(JavaIndex main, Map<String, String> config) {
        this.main = main;
        this.config = config;
    }

    public static RefResolver of(Path root) {
        return new RefResolver(JavaIndex.of(root.resolve("src/main/java")), loadConfig(root.resolve("src/main/resources")));
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
        return Optional.empty();
    }

    public static Map<String, String> loadConfig(Path resources) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isDirectory(resources)) {
            return out;
        }
        try (Stream<Path> files = Files.list(resources)) {
            for (Path f : files.sorted().toList()) {
                String name = f.getFileName().toString();
                if (!name.startsWith("application")) {
                    continue;
                }
                if (name.endsWith(".properties")) {
                    Properties p = new Properties();
                    try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                        p.load(r);
                    }
                    p.forEach((k, v) -> out.putIfAbsent(k.toString(), v.toString()));
                } else if (name.endsWith(".yml") || name.endsWith(".yaml")) {
                    for (JsonNode doc : Json.YAML.readValues(Json.YAML.getFactory().createParser(f.toFile()), JsonNode.class).readAll()) {
                        flatten("", doc, out);
                    }
                }
            }
        } catch (IOException e) {
            // unreadable config is reported by lint as unresolvable refs
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
