package com.gta.core.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.error.GtaException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The parts of pom.xml gta needs (plan §6.1): Boot version, JUnit generation, dependencies, plugins. */
class PomInfoTest {
    @TempDir
    Path root;

    @Test
    void shouldReadTheBootParentDependenciesAndPlugins() throws Exception {
        Files.writeString(
                root.resolve("pom.xml"),
                """
                <project>
                  <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.5.4</version></parent>
                  <artifactId>billing</artifactId>
                  <dependencies><dependency><artifactId>ojdbc11</artifactId></dependency></dependencies>
                  <build><plugins><plugin><artifactId>maven-failsafe-plugin</artifactId></plugin></plugins></build>
                </project>
                """);

        PomInfo pom = PomInfo.read(root);

        assertEquals("billing", pom.artifactId);
        assertEquals(3, pom.bootMajor());
        assertEquals(5, pom.junitMajor(), "Boot 3.5 manages JUnit 5");
        assertTrue(pom.dependencies.contains("ojdbc11"));
        assertTrue(pom.plugins.contains("maven-failsafe-plugin"));
    }

    @Test
    void shouldTreatAProjectWithoutBootAsBootZero() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><artifactId>lib</artifactId></project>");

        PomInfo pom = PomInfo.read(root);

        assertEquals(0, pom.bootMajor());
        assertEquals(5, pom.junitMajor());
    }

    @Test
    void shouldRefuseAMissingOrBrokenPom() throws Exception {
        assertThrows(GtaException.class, () -> PomInfo.read(root));
        Files.writeString(root.resolve("pom.xml"), "<project>");
        assertThrows(GtaException.class, () -> PomInfo.read(root));
    }

    private int javaOf(String properties) throws Exception {
        Files.writeString(
                root.resolve("pom.xml"),
                "<project><artifactId>a</artifactId><properties>" + properties + "</properties></project>");
        return PomInfo.read(root).javaMajor();
    }

    @Test
    void shouldReadTheServiceJavaLevelFromTheUsualProperties() throws Exception {
        assertEquals(21, javaOf("<java.version>21</java.version>"), "the Spring Boot parent's property");
        assertEquals(
                17,
                javaOf("<maven.compiler.release>17</maven.compiler.release><java.version>21</java.version>"),
                "the compiler's own release wins");
        assertEquals(
                25,
                javaOf(
                        "<java.version>25</java.version><maven.compiler.release>${java.version}</maven.compiler.release>"),
                "one property reference is resolved");
        assertEquals(8, javaOf("<maven.compiler.source>1.8</maven.compiler.source>"), "1.8 is Java 8");
        assertEquals(0, javaOf(""), "unknown when the pom does not say");
    }
}
