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

    /**
     * The service module's pom, with what a module usually inherits from the root pom: the Spring Boot version and the
     * Java level. Dependencies and plugins are the module's own.
     */
    public static PomInfo read(Path repository, String module) {
        if (module == null || module.isEmpty()) {
            return read(repository);
        }
        Path moduleRoot = repository.resolve(module);
        if (!Files.exists(moduleRoot.resolve("pom.xml"))) {
            throw new GtaException(
                    ErrorCode.ENV_UNSUPPORTED_BUILD,
                    "build.module is " + module + ", but " + module + "/pom.xml does not exist",
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
        if (!Files.exists(pom)) {
            throw new GtaException(
                    ErrorCode.ENV_UNSUPPORTED_BUILD,
                    "No pom.xml in " + root,
                    "gta v1 supports Maven projects; run gtaw from the module that contains pom.xml");
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
