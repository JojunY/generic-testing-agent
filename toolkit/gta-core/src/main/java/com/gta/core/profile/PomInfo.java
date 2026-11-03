package com.gta.core.profile;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The parts of pom.xml gta needs: parent (Spring Boot version), coordinates, dependencies, modules. */
public final class PomInfo {
    public String groupId;
    public String artifactId;
    public String parentGroupId;
    public String parentArtifactId;
    public String parentVersion;
    public String bootVersion;
    public List<String> dependencies = new ArrayList<>();
    public List<String> modules = new ArrayList<>();
    public List<String> plugins = new ArrayList<>();

    public static PomInfo read(Path root) {
        Path pom = root.resolve("pom.xml");
        if (!Files.exists(pom)) {
            throw new GtaException(ErrorCode.ENV_UNSUPPORTED_BUILD, "No pom.xml in " + root,
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
            throw new GtaException(ErrorCode.ENV_UNSUPPORTED_BUILD, "Cannot parse pom.xml: " + e.getMessage(), "Fix pom.xml");
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
