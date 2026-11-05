package com.gta.core.change;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.gta.core.config.GtaPaths;
import com.gta.core.profile.JavaIndex;
import com.gta.core.profile.RefResolver;
import com.gta.core.util.Git;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What a change touches beyond the files themselves (plan §15, P1 PR mode): config keys whose value changed, and the
 * classes that reach a changed class through injection (the bean graph).
 */
public final class Impact {
    private Impact() {}

    /**
     * Keys of {@code application*.yml|yaml|properties} in the service's main resources whose value differs between
     * {@code base} and the working tree (added and removed keys included).
     */
    public static Set<String> changedConfigKeys(Git git, GtaPaths paths, String base, Collection<String> changedFiles) {
        Set<String> out = new LinkedHashSet<>();
        String prefix = paths.inModule(GtaPaths.MAIN_RESOURCES + "/");
        for (String file : changedFiles) {
            if (!file.startsWith(prefix) || file.indexOf('/', prefix.length()) >= 0) {
                continue;
            }
            String name = file.substring(prefix.length());
            if (!RefResolver.isConfigFile(name)) {
                continue;
            }
            Map<String, String> before = parse(name, git.show(base, file).orElse(""));
            Path now = paths.root().resolve(file);
            Map<String, String> after;
            try {
                after = parse(name, Files.exists(now) ? Files.readString(now, StandardCharsets.UTF_8) : "");
            } catch (IOException e) {
                after = Map.of();
            }
            Set<String> keys = new LinkedHashSet<>(before.keySet());
            keys.addAll(after.keySet());
            for (String key : keys) {
                if (!Objects.equals(before.get(key), after.get(key))) {
                    out.add(key);
                }
            }
        }
        return out;
    }

    private static Map<String, String> parse(String name, String text) {
        try {
            return RefResolver.parseConfig(name, text);
        } catch (IOException e) {
            return Map.of(); // an unparsable file changes every key it used to have; the diff above reports those
        }
    }

    /**
     * The changed classes plus every class that injects one of them through a field or constructor parameter,
     * transitively. Maps each affected class to the changed class it reaches, for the human-readable reason.
     */
    public static Map<String, String> affectedClasses(JavaIndex main, Set<String> changedClasses) {
        Map<String, Set<String>> bySimpleName = new HashMap<>();
        for (String fqcn : main.classNames()) {
            bySimpleName
                    .computeIfAbsent(fqcn.substring(fqcn.lastIndexOf('.') + 1), k -> new LinkedHashSet<>())
                    .add(fqcn);
        }
        Map<String, Set<String>> injectedBy = new HashMap<>(); // dependency -> classes that inject it
        for (JavaIndex.Unit u : main.units()) {
            for (ClassOrInterfaceDeclaration c : u.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                String owner = c.getFullyQualifiedName().orElse(c.getNameAsString());
                Set<String> types = new LinkedHashSet<>();
                for (FieldDeclaration f : c.getFields()) {
                    f.getElementType().ifClassOrInterfaceType(t -> types.add(t.getNameAsString()));
                }
                for (ConstructorDeclaration k : c.getConstructors()) {
                    for (Parameter p : k.getParameters()) {
                        p.getType().ifClassOrInterfaceType(t -> types.add(t.getNameAsString()));
                    }
                }
                for (ClassOrInterfaceType t : c.getImplementedTypes()) {
                    types.add(t.getNameAsString()); // an implementation is affected when its interface changes
                }
                for (String simple : types) {
                    for (String dependency : bySimpleName.getOrDefault(simple, Set.of())) {
                        if (!dependency.equals(owner)) {
                            injectedBy
                                    .computeIfAbsent(dependency, k -> new LinkedHashSet<>())
                                    .add(owner);
                        }
                    }
                }
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String changed : changedClasses) {
            out.put(changed, changed);
            queue.add(changed);
        }
        while (!queue.isEmpty()) {
            String next = queue.poll();
            for (String user : injectedBy.getOrDefault(next, Set.of())) {
                if (!out.containsKey(user)) {
                    out.put(user, out.get(next));
                    queue.add(user);
                }
            }
        }
        return out;
    }

    /** Fully qualified names of the main classes among changed repository-relative files. */
    public static Set<String> changedClasses(GtaPaths paths, Collection<String> changedFiles) {
        Set<String> out = new LinkedHashSet<>();
        String prefix = paths.inModule(GtaPaths.MAIN_JAVA + "/");
        for (String f : changedFiles) {
            if (f.startsWith(prefix) && f.endsWith(".java")) {
                out.add(f.substring(prefix.length(), f.length() - ".java".length())
                        .replace('/', '.'));
            }
        }
        return out;
    }
}
