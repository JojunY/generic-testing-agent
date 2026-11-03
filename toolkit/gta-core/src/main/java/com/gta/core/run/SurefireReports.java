package com.gta.core.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.gta.core.util.Json;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Parses Surefire/Failsafe XML and merges gta-junit scenario tags (target/gta-results/*.jsonl). */
public final class SurefireReports {
    private SurefireReports() {
    }

    public static List<TestCase> parse(List<Path> reportDirs, Path gtaResultsDir) {
        Map<String, JsonNode> tagged = readGtaResults(gtaResultsDir);
        List<TestCase> out = new ArrayList<>();
        for (Path dir : reportDirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.getFileName().toString().startsWith("TEST-") && p.toString().endsWith(".xml")).sorted().toList()) {
                    out.addAll(parseFile(f));
                }
            } catch (IOException e) {
                // a missing report directory simply yields no cases
            }
        }
        for (TestCase c : out) {
            JsonNode n = tagged.get(c.className + "#" + c.method);
            if (n != null) {
                n.path("tags").forEach(t -> c.tags.add(t.asText()));
                c.displayName = n.path("displayName").asText(null);
                if ("known-defect".equals(n.path("outcome").asText())) {
                    c.outcome = "known-defect";
                }
            }
        }
        return out;
    }

    static List<TestCase> parseFile(Path file) {
        List<TestCase> out = new ArrayList<>();
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = f.newDocumentBuilder().parse(file.toFile());
            NodeList cases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                Element e = (Element) cases.item(i);
                TestCase c = new TestCase();
                c.className = e.getAttribute("classname");
                c.method = e.getAttribute("name").replaceAll("\\(.*\\)$", "");
                c.outcome = "passed";
                Element failure = first(e, "failure");
                Element error = first(e, "error");
                if (first(e, "skipped") != null) {
                    c.outcome = "skipped";
                } else if (failure != null || error != null) {
                    Element x = failure != null ? failure : error;
                    c.outcome = failure != null ? "failed" : "error";
                    c.failureType = x.getAttribute("type");
                    c.message = x.getAttribute("message");
                    c.stack = x.getTextContent();
                }
                out.add(c);
            }
        } catch (Exception e) {
            // unreadable report: ignored, the run will be classified from Maven output
        }
        return out;
    }

    private static Element first(Element parent, String tag) {
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n instanceof Element el && el.getTagName().equals(tag)) {
                return el;
            }
        }
        return null;
    }

    static Map<String, JsonNode> readGtaResults(Path dir) {
        Map<String, JsonNode> out = new HashMap<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".jsonl")).toList()) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode n = Json.MAPPER.readTree(line);
                    out.put(n.path("className").asText() + "#" + n.path("method").asText(), n);
                }
            }
        } catch (IOException e) {
            // listener output is optional
        }
        return out;
    }
}
