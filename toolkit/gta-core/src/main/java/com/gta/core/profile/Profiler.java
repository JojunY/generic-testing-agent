package com.gta.core.profile;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.gta.core.config.GtaPaths;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.registry.CapabilityRegistry;
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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Deterministic service profile: build info, detected capabilities and the entrypoint inventory (plan §4.4). */
public final class Profiler {
    private static final Map<String, String> HTTP = Map.of(
            "GetMapping",
            "GET",
            "PostMapping",
            "POST",
            "PutMapping",
            "PUT",
            "PatchMapping",
            "PATCH",
            "DeleteMapping",
            "DELETE",
            "RequestMapping",
            "ANY");
    private static final List<String> SUFFIXES = List.of(
            "Controller",
            "Resource",
            "Endpoint",
            "Api",
            "Listener",
            "Consumer",
            "Job",
            "Scheduler",
            "Task",
            "Handler",
            "Configuration",
            "Config");

    /** Message listener annotations: Kafka, JMS/MQ, Pub/Sub (ServiceActivator), RabbitMQ, SQS, application events. */
    static final List<String> LISTENERS = List.of(
            "KafkaListener",
            "JmsListener",
            "ServiceActivator",
            "RabbitListener",
            "SqsListener",
            "TransactionalEventListener",
            "ApplicationModuleListener");

    private Profiler() {}

    public static ServiceProfile profile(Path root, CapabilityRegistry registry, String configuredBasePackage) {
        return profile(new GtaPaths(root), registry, configuredBasePackage);
    }

    public static ServiceProfile profile(GtaPaths paths, CapabilityRegistry registry, String configuredBasePackage) {
        Path repository = paths.root();
        Path root = paths.moduleRoot();
        PomInfo pom = PomInfo.read(repository, paths.module());
        if (paths.module().isEmpty() && !pom.modules.isEmpty()) {
            // an aggregator pom has no sources of its own: profiling it would find no flows and say nothing
            throw new GtaException(
                    ErrorCode.ENV_UNSUPPORTED_BUILD,
                    "This is a multi-module repository " + pom.modules + " and build.module is not set",
                    "Set build.module in .gta/config.json to the module that holds the service, e.g. {\"build\":"
                            + " {\"module\": \"" + pom.modules.get(0) + "\"}}");
        }
        JavaIndex main = JavaIndex.of(paths.mainJava());
        ServiceProfile p = new ServiceProfile();
        p.module = paths.module();
        p.artifactId = pom.artifactId;
        p.bootVersion = pom.bootVersion;
        p.bootMajor = pom.bootMajor();
        p.junitMajor = pom.junitMajor();
        p.javaMajor = pom.javaMajor();
        p.mavenWrapper = Files.exists(repository.resolve("mvnw")) || Files.exists(repository.resolve("mvnw.cmd"));
        p.dependencies.addAll(pom.dependencies);
        p.basePackage = "auto".equals(configuredBasePackage) || configuredBasePackage == null
                ? detectBasePackage(main)
                : configuredBasePackage;

        String sources = allSources(main);
        for (CapabilityRegistry.Capability c : registry.capabilities) {
            boolean hit = c.detect.buildDeps.stream().anyMatch(pom.dependencies::contains)
                    || c.detect.annotations.stream().anyMatch(sources::contains)
                    || c.detect.files.stream().anyMatch(g -> anyFileMatches(root, g));
            if (hit) {
                (c.activeIn(CapabilityRegistry.CURRENT_PHASE) ? p.capabilities : p.explainOnlyCapabilities).add(c.id);
            }
        }
        p.applicationClass = applicationClass(main);
        p.entrypoints.addAll(entrypoints(main));
        return p;
    }

    static String applicationClass(JavaIndex main) {
        for (JavaIndex.Unit u : main.units()) {
            for (ClassOrInterfaceDeclaration c : u.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                if (c.getAnnotationByName("SpringBootApplication").isPresent()) {
                    return c.getFullyQualifiedName().orElse(c.getNameAsString());
                }
            }
        }
        return null;
    }

    static String detectBasePackage(JavaIndex main) {
        for (JavaIndex.Unit u : main.units()) {
            for (ClassOrInterfaceDeclaration c : u.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                if (c.getAnnotationByName("SpringBootApplication").isPresent()) {
                    return u.cu().getPackageDeclaration()
                            .map(d -> d.getNameAsString())
                            .orElse("");
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
            return files.filter(f ->
                            !f.toString().contains("/.git/") && !f.toString().contains("/target/"))
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
                boolean controller = c.getAnnotationByName("RestController").isPresent()
                        || c.getAnnotationByName("Controller").isPresent();
                String classPath = c.getAnnotationByName("RequestMapping")
                        .map(Profiler::pathsOf)
                        .filter(p -> !p.isEmpty())
                        .map(p -> p.get(0))
                        .orElse("");
                for (MethodDeclaration m : c.getMethods()) {
                    ServiceProfile.EntryPoint e = null;
                    if (controller) {
                        for (AnnotationExpr a : m.getAnnotations()) {
                            String verb = HTTP.get(a.getNameAsString());
                            if (verb != null) {
                                e = base(c, fqcn, m, "rest-api", "http");
                                e.httpMethod = verb;
                                List<String> paths = pathsOf(a);
                                e.path = (paths.isEmpty() ? List.of("") : paths)
                                        .stream()
                                                .map(p -> (classPath + p).replaceAll("//+", "/"))
                                                .collect(Collectors.joining(" | "));
                                e.risk = verb.equals("GET") ? 2 : 6;
                            }
                        }
                    }
                    if (m.getAnnotationByName("Scheduled").isPresent()) {
                        e = base(c, fqcn, m, "scheduler", "schedule");
                        e.trigger = m.getAnnotationByName("Scheduled").get().toString();
                        e.risk = 6;
                    }
                    if (isQuartzJob(c)
                            && (m.getNameAsString().equals("execute")
                                    || m.getNameAsString().equals("executeInternal"))) {
                        e = base(c, fqcn, m, "scheduler", "schedule");
                        e.trigger = "Quartz job " + c.getNameAsString();
                        e.risk = 6;
                    }
                    if (m.getAnnotationByName("Bean").isPresent()
                            && m.getType().asString().equals("Job")) {
                        // a Spring Batch job is a @Bean of type Job; the flow is the whole job (plan §7.4)
                        e = base(c, fqcn, m, "spring-batch", "job");
                        e.trigger = "Spring Batch job " + m.getNameAsString();
                        e.risk = 6;
                    }
                    for (String listener : LISTENERS) {
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

    /** A Quartz job: extends QuartzJobBean or implements org.quartz.Job. */
    static boolean isQuartzJob(ClassOrInterfaceDeclaration c) {
        return c.getExtendedTypes().stream().anyMatch(t -> t.getNameAsString().equals("QuartzJobBean"))
                || c.getImplementedTypes().stream()
                        .anyMatch(t -> t.getNameAsString().equals("Job"));
    }

    private static ServiceProfile.EntryPoint base(
            ClassOrInterfaceDeclaration c, String fqcn, MethodDeclaration m, String capability, String kind) {
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

    /** Every path of a mapping annotation ({@code @GetMapping({"/a", "/b"})} has two). */
    static List<String> pathsOf(AnnotationExpr a) {
        Expression value = null;
        if (a instanceof SingleMemberAnnotationExpr s) {
            value = s.getMemberValue();
        } else if (a instanceof NormalAnnotationExpr n) {
            for (MemberValuePair pair : n.getPairs()) {
                if (pair.getNameAsString().equals("value")
                        || pair.getNameAsString().equals("path")) {
                    value = pair.getValue();
                }
            }
        }
        if (value == null) {
            return List.of();
        }
        List<Expression> values =
                value.isArrayInitializerExpr() ? value.asArrayInitializerExpr().getValues() : List.of(value);
        return values.stream()
                .filter(Expression::isStringLiteralExpr)
                .map(v -> v.asStringLiteralExpr().getValue())
                .toList();
    }
}
