package com.gta.core.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The parsed source tree refs are resolved against (plan §3.3). */
class JavaIndexTest {
    @TempDir
    Path src;

    private void write(String rel, String code) throws Exception {
        Files.createDirectories(src.resolve(rel).getParent());
        Files.writeString(src.resolve(rel), code);
    }

    @Test
    void shouldIndexClassesNestedClassesAndMethodBodies() throws Exception {
        write(
                "com/acme/Pricing.java",
                "package com.acme;\nclass Pricing { int discount() { return 10; } static class Rule { void apply() {} } }\n");
        write("com/acme/Broken.java", "package com.acme; class Broken {");

        JavaIndex index = JavaIndex.of(src);

        assertTrue(
                index.classNames().containsAll(Set.of("com.acme.Pricing", "com.acme.Pricing.Rule")),
                index.classNames().toString());
        assertTrue(index.hasClass("com.acme.Pricing"));
        assertFalse(index.hasClass("com.acme.Broken"), "an unparseable file is skipped");
        assertEquals(Optional.of(src.resolve("com/acme/Pricing.java")), index.fileOf("com.acme.Pricing"));
        assertTrue(index.methodHash("com.acme.Pricing#discount").isPresent());
        assertEquals(Optional.empty(), index.methodHash("com.acme.Pricing#missing"));
        assertEquals(Optional.empty(), index.methodHash("com.acme.Missing#x"));
    }

    @Test
    void shouldIgnoreWhitespaceButNotCodeInAMethodHash() throws Exception {
        write("A.java", "class A { int m() { return 1; } }");
        String before = JavaIndex.of(src).methodHash("A#m").orElseThrow();

        write("A.java", "class A {\n  int m() {\n    return 1;\n  }\n}");
        assertEquals(before, JavaIndex.of(src).methodHash("A#m").orElseThrow());

        write("A.java", "class A { int m() { return 2; } }");
        assertNotEquals(before, JavaIndex.of(src).methodHash("A#m").orElseThrow());
    }

    @Test
    void shouldReadAMissingRootAsEmpty() {
        assertTrue(JavaIndex.of(src.resolve("none")).units().isEmpty());
    }
}
