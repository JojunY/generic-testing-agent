package com.gta.core.profile;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** The parts of pom.xml gta needs: parent (Spring Boot version), coordinates, dependencies, modules. */
public final class PomInfo {
    public String groupId;
    public String artifactId;
    public String parentGroupId;
    public String parentArtifactId;
    public String parentVersion;
    public String bootVersion;
    /** The service's Java release from its properties ({@code maven.compiler.release}, {@code java.version}, …); null if not set. */
    public String javaRelease;

    public List<String> dependencies = new ArrayList<>();
    public List<String> modules = new ArrayList<>();
    public List<String> plugins = new ArrayList<>();
    /** {@code maven} or {@code gradle}: which build file this was read from. */
    public String tool = "maven";

    /**
     * The service module's pom, with what a module usually inherits from the root pom: the Spring Boot version and the
     * Java level. Dependencies and plugins are the module's own.
     */
    public static PomInfo read(Path repository, String module) {
        if (module == null || module.isEmpty()) {
            return read(repository);
        }
        Path moduleRoot = repository.resolve(module);
        if (!Files.exists(moduleRoot.resolve("pom.xml"))
                && gradleBuildFile(moduleRoot).isEmpty()) {
            throw new GtaException(
                    ErrorCode.ENV_UNSUPPORTED_BUILD,
                    "build.module is " + module + ", but " + module + " has no pom.xml or build.gradle(.kts)",
                    "Set build.module in .gta/config.json to the folder of the module that holds the service");
        }
        PomInfo info = read(moduleRoot);
        PomInfo parent = read(repository);
        if (info.bootVersion == null) {
            info.bootVersion = parent.bootVersion;
        }
        if (info.javaRelease == null) {
            info.javaRelease = parent.javaRelease;
        }
        return info;
    }

    public static PomInfo read(Path root) {
        Path pom = root.resolve("pom.xml");
        if (!Files.exists(pom) && gradleBuildFile(root).isPresent()) {
            return readGradle(root);
        }
        if (!Files.exists(pom)) {
            throw new GtaException(
                    ErrorCode.ENV_UNSUPPORTED_BUILD,
                    "No pom.xml in " + root,
                    "gta supports Maven and Gradle builds; run gtaw where pom.xml or build.gradle(.kts) is");
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = f.newDocumentBuilder().parse(pom.toFile());
            Element project = doc.getDocumentElement();
            PomInfo info = new PomInfo();
            info.groupId = child(project, "groupId");
            info.artifactId = child(project, "artifactId");
            Element parent = childElement(project, "parent");
            if (parent != null) {
                info.parentGroupId = child(parent, "groupId");
                info.parentArtifactId = child(parent, "artifactId");
                info.parentVersion = child(parent, "version");
                if (info.groupId == null) {
                    info.groupId = info.parentGroupId;
                }
                if ("spring-boot-starter-parent".equals(info.parentArtifactId)) {
                    info.bootVersion = info.parentVersion;
                }
            }
            Element deps = childElement(project, "dependencies");
            if (deps != null) {
                for (Element d : children(deps, "dependency")) {
                    info.dependencies.add(child(d, "artifactId"));
                }
            }
            Element mgmt = childElement(project, "dependencyManagement");
            if (mgmt != null && info.bootVersion == null) {
                Element md = childElement(mgmt, "dependencies");
                if (md != null) {
                    for (Element d : children(md, "dependency")) {
                        if ("spring-boot-dependencies".equals(child(d, "artifactId"))) {
                            info.bootVersion = child(d, "version");
                        }
                    }
                }
            }
            Element props = childElement(project, "properties");
            if (props != null) {
                for (String key : List.of(
                        "maven.compiler.release", "java.version", "maven.compiler.target", "maven.compiler.source")) {
                    String v = resolve(child(props, key), props);
                    if (v != null && !v.isBlank() && !v.startsWith("$")) {
                        info.javaRelease = v.startsWith("1.") ? v.substring(2) : v;
                        break;
                    }
                }
            }
            Element modules = childElement(project, "modules");
            if (modules != null) {
                for (Element m : children(modules, "module")) {
                    info.modules.add(m.getTextContent().trim());
                }
            }
            Element build = childElement(project, "build");
            if (build != null) {
                Element plugins = childElement(build, "plugins");
                if (plugins != null) {
                    for (Element p : children(plugins, "plugin")) {
                        info.plugins.add(child(p, "artifactId"));
                    }
                }
            }
            return info;
        } catch (GtaException e) {
            throw e;
        } catch (Exception e) {
            throw new GtaException(
                    ErrorCode.ENV_UNSUPPORTED_BUILD, "Cannot parse pom.xml: " + e.getMessage(), "Fix pom.xml");
        }
    }

    /** {@code build.gradle.kts} or {@code build.gradle} of a directory. */
    public static java.util.Optional<Path> gradleBuildFile(Path dir) {
        for (String name : List.of("build.gradle.kts", "build.gradle")) {
            if (Files.exists(dir.resolve(name))) {
                return java.util.Optional.of(dir.resolve(name));
            }
        }
        return java.util.Optional.empty();
    }

    private static final java.util.regex.Pattern BOOT_PLUGIN = java.util.regex.Pattern.compile(
            "id\\s*\\(?\\s*[\"']org\\.springframework\\.boot[\"']\\s*\\)?\\s*version\\s*\\(?\\s*[\"']([^\"']+)[\"']");
    private static final java.util.regex.Pattern DEPENDENCY = java.util.regex.Pattern.compile(
            "\\b(?:implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|developmentOnly)"
                    + "\\s*\\(?\\s*[\"']([^:\"']+):([^:\"']+)(?::[^\"']*)?[\"']");
    private static final java.util.regex.Pattern JAVA_LEVEL = java.util.regex.Pattern.compile(
            "JavaLanguageVersion\\.of\\(\\s*(\\d+)\\s*\\)|(?:source|target)Compatibility\\s*=\\s*(?:JavaVersion\\.VERSION_(?:1_)?(\\d+)|[\"']?(?:1\\.)?(\\d+)[\"']?)");
    private static final java.util.regex.Pattern INCLUDE =
            java.util.regex.Pattern.compile("(?m)^\\s*include\\s*\\(?([^)\\n]+)\\)?");

    /**
     * The parts of a Gradle build gta needs, read from the build script text (Groovy or Kotlin DSL): the Spring Boot
     * plugin version, the declared dependencies, the Java toolchain or compatibility level, and the subprojects of
     * settings.gradle(.kts). gta does not run Gradle to read them, so a value computed in the script is not seen.
     */
    static PomInfo readGradle(Path root) {
        PomInfo info = new PomInfo();
        info.tool = "gradle";
        info.artifactId = String.valueOf(root.toAbsolutePath().normalize().getFileName());
        try {
            String build =
                    Files.readString(gradleBuildFile(root).orElseThrow(), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher boot = BOOT_PLUGIN.matcher(build);
            if (boot.find()) {
                info.bootVersion = boot.group(1);
            }
            java.util.regex.Matcher dep = DEPENDENCY.matcher(build);
            while (dep.find()) {
                info.dependencies.add(dep.group(2));
            }
            java.util.regex.Matcher level = JAVA_LEVEL.matcher(build);
            if (level.find()) {
                for (int g = 1; g <= 3; g++) {
                    if (level.group(g) != null) {
                        info.javaRelease = level.group(g);
                        break;
                    }
                }
            }
            for (String name : List.of("settings.gradle.kts", "settings.gradle")) {
                Path settings = root.resolve(name);
                if (Files.exists(settings)) {
                    String text = Files.readString(settings, java.nio.charset.StandardCharsets.UTF_8);
                    java.util.regex.Matcher m = INCLUDE.matcher(text);
                    while (m.find()) {
                        for (String part : m.group(1).split(",")) {
                            String module = part.strip()
                                    .replaceAll("^[\"']|[\"']$", "")
                                    .replaceAll("^:", "")
                                    .replace(':', '/');
                            if (!module.isEmpty()) {
                                info.modules.add(module);
                            }
                        }
                    }
                    java.util.regex.Matcher project = java.util.regex.Pattern.compile(
                                    "rootProject\\.name\\s*=\\s*[\"']([^\"']+)[\"']")
                            .matcher(text);
                    if (project.find()) {
                        info.artifactId = project.group(1);
                    }
                    break;
                }
            }
            return info;
        } catch (java.io.IOException e) {
            throw new GtaException(
                    ErrorCode.ENV_UNSUPPORTED_BUILD,
                    "Cannot read the Gradle build: " + e.getMessage(),
                    "Fix the build file");
        }
    }

    /** 4 for Spring Boot 4.x, 3 for 3.x, 0 when Boot is not used. */
    public int bootMajor() {
        if (bootVersion == null || bootVersion.isBlank() || bootVersion.startsWith("$")) {
            return 0;
        }
        try {
            return Integer.parseInt(bootVersion.split("\\.")[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** JUnit follows the Spring Boot parent: Boot 4 manages JUnit 6, Boot 3.5 manages JUnit 5. */
    public int junitMajor() {
        return bootMajor() >= 4 ? 6 : 5;
    }

    /** Resolves one {@code ${property}} reference against the same properties block. */
    private static String resolve(String value, Element props) {
        if (value != null && value.startsWith("${") && value.endsWith("}")) {
            return child(props, value.substring(2, value.length() - 1));
        }
        return value;
    }

    /** The Java release as a number (17, 21, …), 0 when unknown. */
    public int javaMajor() {
        try {
            return javaRelease == null ? 0 : Integer.parseInt(javaRelease.split("\\.")[0].strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String child(Element e, String name) {
        Element c = childElement(e, name);
        return c == null ? null : c.getTextContent().trim();
    }

    private static Element childElement(Element e, String name) {
        NodeList list = e.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n instanceof Element el && el.getTagName().equals(name)) {
                return el;
            }
        }
        return null;
    }

    private static List<Element> children(Element e, String name) {
        List<Element> out = new ArrayList<>();
        NodeList list = e.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n instanceof Element el && el.getTagName().equals(name)) {
                out.add(el);
            }
        }
        return out;
    }
}
