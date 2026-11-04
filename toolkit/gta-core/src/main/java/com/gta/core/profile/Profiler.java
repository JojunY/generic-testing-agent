package com.gta.core.profile;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.gta.core.config.GtaPaths;
import com.gta.core.registry.Registry;
import com.gta.core.util.Ids;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Deterministic service profile: build info, detected capabilities and the entrypoint inventory (plan §4.4). */
public final class Profiler {
    private static final Map<String, String> HTTP = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
            "PatchMapping", "PATCH", "DeleteMapping", "DELETE", "RequestMapping", "ANY");
    private static final List<String> SUFFIXES = List.of("Controller", "Resource", "Endpoint", "Api", "Listener",
            "Consumer", "Job", "Scheduler", "Task", "Handler");

    private Profiler() {
    }

    public static ServiceProfile profile(Path root, Registry registry, String configuredBasePackage) {
        PomInfo pom = PomInfo.read(root);
        JavaIndex main = JavaIndex.of(root.resolve(GtaPaths.MAIN_JAVA));
        ServiceProfile p = new ServiceProfile();
        p.artifactId = pom.artifactId;
        p.bootVersion = pom.bootVersion;
        p.bootMajor = pom.bootMajor();
        p.junitMajor = pom.junitMajor();
        p.mavenWrapper = Files.exists(root.resolve("mvnw")) || Files.exists(root.resolve("mvnw.cmd"));
        p.dependencies.addAll(pom.dependencies);
        p.basePackage = "auto".equals(configuredBasePackage) || configuredBasePackage == null
                ? detectBasePackage(main) : configuredBasePackage;

        String sources = allSources(main);
        for (Registry.Capability c : registry.capabilities) {
            boolean hit = c.detect.buildDeps.stream().anyMatch(pom.dependencies::contains)
                    || c.detect.annotations.stream().anyMatch(sources::contains)
                    || c.detect.files.stream().anyMatch(g -> anyFileMatches(root, g));
            if (hit) {
                (c.activeIn(Registry.CURRENT_PHASE) || !c.dispatch ? p.capabilities : p.explainOnlyCapabilities).add(c.id);
            }
        }
        p.entrypoints.addAll(entrypoints(main));
        return p;
    }

    static String detectBasePackage(JavaIndex main) {
        for (JavaIndex.Unit u : main.units()) {
            for (ClassOrInterfaceDeclaration c : u.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                if (c.getAnnotationByName("SpringBootApplication").isPresent()) {
                    return u.cu().getPackageDeclaration().map(d -> d.getNameAsString()).orElse("");
                }
            }
        }
        // fallback: longest common package prefix of all production classes
        String prefix = null;
        for (String fqcn : main.classNames()) {
            String pkg = fqcn.contains(".") ? fqcn.substring(0, fqcn.lastIndexOf('.')) : "";
            prefix = prefix == null ? pkg : commonPrefix(prefix, pkg);
        }
        return prefix == null ? "" : prefix;
    }

    private static String commonPrefix(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(x.length, y.length) && x[i].equals(y[i]); i++) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(x[i]);
        }
        return sb.toString();
    }

    private static String allSources(JavaIndex main) {
        StringBuilder sb = new StringBuilder();
        main.units().forEach(u -> sb.append(u.cu().toString()).append('\n'));
        return sb.toString();
    }

    private static boolean anyFileMatches(Path root, String glob) {
        PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        try (Stream<Path> files = Files.walk(root, 8)) {
            return files.filter(f -> !f.toString().contains("/.git/") && !f.toString().contains("/target/"))
                    .anyMatch(f -> m.matches(root.relativize(f)));
        } catch (IOException e) {
            return false;
        }
    }

    static List<ServiceProfile.EntryPoint> entrypoints(JavaIndex main) {
        Set<String> seenFlowIds = new LinkedHashSet<>();
        List<ServiceProfile.EntryPoint> out = new ArrayList<>();
        for (JavaIndex.Unit u : main.units()) {
            for (ClassOrInterfaceDeclaration c : u.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                String fqcn = c.getFullyQualifiedName().orElse(c.getNameAsString());
                boolean controller = c.getAnnotationByName("RestController").isPresent() || c.getAnnotationByName("Controller").isPresent();
                String classPath = c.getAnnotationByName("RequestMapping").flatMap(Profiler::pathOf).orElse("");
                for (MethodDeclaration m : c.getMethods()) {
                    ServiceProfile.EntryPoint e = null;
                    if (controller) {
                        for (AnnotationExpr a : m.getAnnotations()) {
                            String verb = HTTP.get(a.getNameAsString());
                            if (verb != null) {
                                e = base(c, fqcn, m, "rest-api", "http");
                                e.httpMethod = verb;
                                e.path = (classPath + pathOf(a).orElse("")).replaceAll("//+", "/");
                                e.risk = verb.equals("GET") ? 2 : 6;
                            }
                        }
                    }
                    if (m.getAnnotationByName("Scheduled").isPresent()) {
                        e = base(c, fqcn, m, "scheduler", "schedule");
                        e.trigger = m.getAnnotationByName("Scheduled").get().toString();
                        e.risk = 6;
                    }
                    for (String listener : List.of("KafkaListener", "JmsListener", "ServiceActivator")) {
                        if (m.getAnnotationByName(listener).isPresent()) {
                            e = base(c, fqcn, m, "messaging", "listener");
                            e.trigger = m.getAnnotationByName(listener).get().toString();
                            e.risk = 6;
                        }
                    }
                    if (e != null) {
                        String id = e.flowId;
                        int n = 2;
                        while (!seenFlowIds.add(e.flowId)) {
                            e.flowId = id + "-" + n++;
                        }
                        out.add(e);
                    }
                }
            }
        }
        return out;
    }

    private static ServiceProfile.EntryPoint base(ClassOrInterfaceDeclaration c, String fqcn, MethodDeclaration m,
                                                  String capability, String kind) {
        ServiceProfile.EntryPoint e = new ServiceProfile.EntryPoint();
        String name = c.getNameAsString();
        for (String suffix : SUFFIXES) {
            if (name.endsWith(suffix) && name.length() > suffix.length()) {
                name = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        String domain = Ids.kebab(name);
        if (domain.isEmpty() || domain.equals("foundation")) {
            domain = "app";
        }
        e.flowId = domain + "." + Ids.kebab(m.getNameAsString());
        e.capability = capability;
        e.kind = kind;
        e.ref = "method:" + fqcn + "#" + m.getNameAsString();
        return e;
    }

    private static Optional<String> pathOf(AnnotationExpr a) {
        Expression value = null;
        if (a instanceof SingleMemberAnnotationExpr s) {
            value = s.getMemberValue();
        } else if (a instanceof NormalAnnotationExpr n) {
            for (MemberValuePair pair : n.getPairs()) {
                if (pair.getNameAsString().equals("value") || pair.getNameAsString().equals("path")) {
                    value = pair.getValue();
                }
            }
        }
        if (value == null) {
            return Optional.empty();
        }
        if (value.isArrayInitializerExpr() && !value.asArrayInitializerExpr().getValues().isEmpty()) {
            value = value.asArrayInitializerExpr().getValues().get(0);
        }
        return value.isStringLiteralExpr() ? Optional.of(value.asStringLiteralExpr().getValue()) : Optional.empty();
    }
}
