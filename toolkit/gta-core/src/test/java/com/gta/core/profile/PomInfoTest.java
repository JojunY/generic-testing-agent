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
        Files.writeString(root.resolve("pom.xml"), """
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

    @Test
    void shouldReadAModuleWithTheBootVersionAndJavaLevelItInheritsFromTheRootPom() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.5.4</version></parent>
                  <artifactId>orders</artifactId>
                  <properties><java.version>21</java.version></properties>
                  <modules><module>order-api</module><module>order-core</module></modules>
                </project>
                """);
        Files.createDirectories(root.resolve("order-api"));
        Files.writeString(root.resolve("order-api/pom.xml"), """
                <project>
                  <parent><artifactId>orders</artifactId></parent>
                  <artifactId>order-api</artifactId>
                  <dependencies><dependency><artifactId>spring-boot-starter-web</artifactId></dependency></dependencies>
                </project>
                """);

        PomInfo api = PomInfo.read(root, "order-api");

        assertEquals("order-api", api.artifactId);
        assertEquals("3.5.4", api.bootVersion, "inherited from the root pom's parent");
        assertEquals(21, api.javaMajor(), "inherited from the root pom's properties");
        assertEquals(
                java.util.List.of("spring-boot-starter-web"), api.dependencies, "dependencies are the module's own");
        assertThrows(GtaException.class, () -> PomInfo.read(root, "no-such-module"));
    }

    @Test
    void shouldReadAKotlinGradleBuildWhenThereIsNoPom() throws Exception {
        Files.writeString(root.resolve("settings.gradle.kts"), """
                rootProject.name = "invoice"
                include("invoice-api", ":tools:cli")
                """);
        Files.writeString(root.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("org.springframework.boot") version "3.4.1"
                }
                java { sourceCompatibility = JavaVersion.VERSION_21 }
                dependencies {
                    implementation("org.springframework.boot:spring-boot-starter-webmvc")
                    testImplementation("com.gta:gta-junit:0.1.0")
                }
                """);

        PomInfo build = PomInfo.read(root);

        assertEquals("gradle", build.tool);
        assertEquals("invoice", build.artifactId);
        assertEquals(3, build.bootMajor());
        assertEquals(21, build.javaMajor());
        assertEquals(java.util.List.of("spring-boot-starter-webmvc", "gta-junit"), build.dependencies);
        assertEquals(java.util.List.of("invoice-api", "tools/cli"), build.modules);
    }

    @Test
    void shouldReadAGroovyGradleBuildWithAToolchain() throws Exception {
        Files.writeString(root.resolve("build.gradle"), """
                plugins {
                    id 'org.springframework.boot' version '2.7.18'
                }
                java { toolchain { languageVersion = JavaLanguageVersion.of(11) } }
                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter-web'
                }
                """);

        PomInfo build = PomInfo.read(root);

        assertEquals(2, build.bootMajor());
        assertEquals(11, build.javaMajor());
        assertEquals(java.util.List.of("spring-boot-starter-web"), build.dependencies);
        assertTrue(build.modules.isEmpty());
        assertEquals(java.util.Optional.of(root.resolve("build.gradle")), PomInfo.gradleBuildFile(root));
    }
}
