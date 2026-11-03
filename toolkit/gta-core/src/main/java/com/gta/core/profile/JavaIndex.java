package com.gta.core.profile;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.gta.core.util.Hashes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Parsed view of a source root (src/main/java or src/test/java). */
public final class JavaIndex {
    public record Unit(Path file, CompilationUnit cu) {
    }

    private final List<Unit> units = new ArrayList<>();
    private final Map<String, String> methodHashes = new LinkedHashMap<>();
    private final Map<String, Path> classFiles = new LinkedHashMap<>();

    public static JavaParser parser() {
        return new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    }

    public static JavaIndex of(Path sourceRoot) {
        JavaIndex index = new JavaIndex();
        if (!Files.isDirectory(sourceRoot)) {
            return index;
        }
        JavaParser parser = parser();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                ParseResult<CompilationUnit> result = parser.parse(file);
                result.getResult().ifPresent(cu -> index.add(file, cu));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return index;
    }

    private void add(Path file, CompilationUnit cu) {
        units.add(new Unit(file, cu));
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            String fqcn = type.getFullyQualifiedName().orElse(pkg + type.getNameAsString());
            classFiles.put(fqcn, file);
            Map<String, StringBuilder> bodies = new LinkedHashMap<>();
            for (Object m : type.getMethods()) {
                MethodDeclaration md = (MethodDeclaration) m;
                bodies.computeIfAbsent(md.getNameAsString(), k -> new StringBuilder())
                        .append(md.getDeclarationAsString()).append(md.getBody().map(Object::toString).orElse(""));
            }
            bodies.forEach((name, body) -> methodHashes.put(fqcn + "#" + name, Hashes.normalizedShort(body.toString())));
        }
    }

    public List<Unit> units() {
        return units;
    }

    public Optional<String> methodHash(String fqcnAndMethod) {
        return Optional.ofNullable(methodHashes.get(fqcnAndMethod));
    }

    public boolean hasClass(String fqcn) {
        return classFiles.containsKey(fqcn);
    }

    public Optional<Path> fileOf(String fqcn) {
        return Optional.ofNullable(classFiles.get(fqcn));
    }

    public java.util.Set<String> classNames() {
        return classFiles.keySet();
    }
}
