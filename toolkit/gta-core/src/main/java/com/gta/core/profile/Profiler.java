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
            "WorkflowImpl",
            "Workflow",
            "StateMachineConfig",
            "GrpcService",
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

    /** Spring for GraphQL handler annotations (graphql-api). */
    static final List<String> GRAPHQL =
            List.of("QueryMapping", "MutationMapping", "SubscriptionMapping", "SchemaMapping", "BatchMapping");

    private Profiler() {}

    public static ServiceProfile profile(Path root, CapabilityRegistry registry, String configuredBasePackage) {
        return profile(new GtaPaths(root), registry, configuredBasePackage);
    }

    public static ServiceProfile profile(GtaPaths paths, CapabilityRegistry registry, String configuredBasePackage) {
        return profile(paths, registry, configuredBasePackage, CapabilityRegistry.CURRENT_PHASE);
    }

    public static ServiceProfile profile(
            GtaPaths paths, CapabilityRegistry registry, String configuredBasePackage, String phase) {
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
                (c.activeIn(phase) ? p.capabilities : p.explainOnlyCapabilities).add(c.id);
            }
        }
        p.applicationClass = applicationClass(main);
        p.entrypoints.addAll(entrypoints(main));
        p.entrypoints.addAll(migrations(paths.mainResources()));
        p.entrypoints.addAll(processes(paths.mainResources()));
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
        Map<String, Set<String>> workflowMethods = temporalWorkflowMethods(main);
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
                                // a handler returning Mono/Flux is a reactive (WebFlux) endpoint: tested differently
                                String returns = m.getType().asString();
                                boolean reactive = returns.startsWith("Mono") || returns.startsWith("Flux");
                                e = base(c, fqcn, m, reactive ? "webflux" : "rest-api", "http");
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
                    if (isGrpcService(c)
                            && m.getAnnotationByName("Override").isPresent()
                            && m.getParameters().stream()
                                    .anyMatch(par -> par.getType().asString().startsWith("StreamObserver"))) {
                        e = base(c, fqcn, m, "grpc-api", "rpc");
                        String implBase = c.getExtendedTypes().get(0).getNameAsString();
                        e.trigger = "gRPC " + implBase.replace("ImplBase", "") + "." + m.getNameAsString();
                        e.risk = 6;
                    }
                    for (String mapping : GRAPHQL) {
                        if (m.getAnnotationByName(mapping).isPresent()) {
                            e = base(c, fqcn, m, "graphql-api", "graphql");
                            e.trigger =
                                    "GraphQL " + mapping.replace("Mapping", "").toLowerCase(java.util.Locale.ROOT) + " "
                                            + m.getNameAsString();
                            e.risk = mapping.equals("MutationMapping") ? 6 : 3;
                        }
                    }
                    if (isTemporalWorkflowMethod(c, m, workflowMethods)) {
                        // the flow is the implementation of a @WorkflowMethod, tested in TestWorkflowEnvironment
                        e = base(c, fqcn, m, "workflow", "workflow");
                        e.trigger = "Temporal workflow " + c.getNameAsString() + "." + m.getNameAsString();
                        e.risk = 6;
                    }
                    if (isStateMachineConfig(c)
                            && m.getNameAsString().equals("configure")
                            && m.getParameters().stream()
                                    .anyMatch(par ->
                                            par.getType().asString().startsWith("StateMachineTransitionConfigurer"))) {
                        // one flow per state machine: its transitions
                        e = base(c, fqcn, m, "workflow", "state-machine");
                        e.flowId = e.flowId.substring(0, e.flowId.indexOf('.')) + ".transitions";
                        e.trigger = "Spring Statemachine " + c.getNameAsString();
                        e.risk = 6;
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

    private static final java.util.regex.Pattern FLYWAY = java.util.regex.Pattern.compile("V([0-9._]+)__(.+)\\.sql");

    /**
     * Schema migrations as flows (db-migration): each Flyway {@code db/migration/V<version>__<name>.sql} and each
     * Liquibase changelog under {@code db/changelog/}. The flow is {@code db.<name>}; the spec cites the file with
     * {@code ref=file:<path>}.
     */
    static List<ServiceProfile.EntryPoint> migrations(Path mainResources) {
        List<ServiceProfile.EntryPoint> out = new ArrayList<>();
        for (String dir : List.of("db/migration", "db/changelog")) {
            Path folder = mainResources.resolve(dir);
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (Stream<Path> files = Files.list(folder)) {
                for (Path f : files.sorted().toList()) {
                    String name = String.valueOf(f.getFileName());
                    java.util.regex.Matcher flyway = FLYWAY.matcher(name);
                    String flowName;
                    String trigger;
                    if (dir.equals("db/migration") && flyway.matches()) {
                        flowName = flyway.group(2);
                        trigger = "Flyway V" + flyway.group(1);
                    } else if (dir.equals("db/changelog") && name.matches(".+\\.(xml|ya?ml|sql)")) {
                        flowName = name.substring(0, name.lastIndexOf('.'));
                        trigger = "Liquibase " + name;
                    } else {
                        continue;
                    }
                    ServiceProfile.EntryPoint e = new ServiceProfile.EntryPoint();
                    e.flowId = "db."
                            + flowName.toLowerCase(java.util.Locale.ROOT)
                                    .replaceAll("[^a-z0-9]+", "-")
                                    .replaceAll("^-|-$", "");
                    e.capability = "db-migration";
                    e.kind = "migration";
                    e.ref = "file:" + GtaPaths.MAIN_RESOURCES + "/" + dir + "/" + name;
                    e.trigger = trigger;
                    e.risk = 6;
                    out.add(e);
                }
            } catch (IOException ex) {
                // unreadable resources: no migration flows; the profile still lists the code's entry points
            }
        }
        return out;
    }

    /**
     * Camunda/BPMN process definitions as flows (workflow): each {@code *.bpmn} under the main resources is
     * {@code process.<name>}, cited with {@code ref=file:}; its delegates are tested through the process.
     */
    static List<ServiceProfile.EntryPoint> processes(Path mainResources) {
        List<ServiceProfile.EntryPoint> out = new ArrayList<>();
        if (!Files.isDirectory(mainResources)) {
            return out;
        }
        try (Stream<Path> files = Files.walk(mainResources, 6)) {
            for (Path f :
                    files.filter(x -> x.toString().endsWith(".bpmn")).sorted().toList()) {
                String name = String.valueOf(f.getFileName());
                ServiceProfile.EntryPoint e = new ServiceProfile.EntryPoint();
                e.flowId = "process." + Ids.kebab(name.substring(0, name.length() - ".bpmn".length()));
                e.capability = "workflow";
                e.kind = "process";
                e.ref = "file:" + GtaPaths.MAIN_RESOURCES + "/"
                        + mainResources.relativize(f).toString().replace('\\', '/');
                e.trigger = "BPMN process " + name;
                e.risk = 6;
                out.add(e);
            }
        } catch (IOException ex) {
            // unreadable resources: no process flows
        }
        return out;
    }

    /** Simple names of Temporal {@code @WorkflowInterface}s and their {@code @WorkflowMethod}s. */
    static Map<String, Set<String>> temporalWorkflowMethods(JavaIndex main) {
        Map<String, Set<String>> out = new java.util.HashMap<>();
        for (JavaIndex.Unit u : main.units()) {
            for (ClassOrInterfaceDeclaration c : u.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                if (c.isInterface()
                        && c.getAnnotationByName("WorkflowInterface").isPresent()) {
                    for (MethodDeclaration m : c.getMethods()) {
                        if (m.getAnnotationByName("WorkflowMethod").isPresent()) {
                            out.computeIfAbsent(c.getNameAsString(), k -> new LinkedHashSet<>())
                                    .add(m.getNameAsString());
                        }
                    }
                }
            }
        }
        return out;
    }

    /** A method of a class that implements a Temporal workflow interface, overriding its @WorkflowMethod. */
    static boolean isTemporalWorkflowMethod(
            ClassOrInterfaceDeclaration c, MethodDeclaration m, Map<String, Set<String>> workflowMethods) {
        return !c.isInterface()
                && c.getImplementedTypes().stream()
                        .anyMatch(t -> workflowMethods
                                .getOrDefault(t.getNameAsString(), Set.of())
                                .contains(m.getNameAsString()));
    }

    /** A Spring Statemachine configuration: extends (Enum)StateMachineConfigurerAdapter. */
    static boolean isStateMachineConfig(ClassOrInterfaceDeclaration c) {
        return c.getExtendedTypes().stream()
                .anyMatch(t -> t.getNameAsString().endsWith("StateMachineConfigurerAdapter"));
    }

    /** A gRPC service: extends the generated {@code XxxGrpc.XxxImplBase}. */
    static boolean isGrpcService(ClassOrInterfaceDeclaration c) {
        return c.getExtendedTypes().stream().anyMatch(t -> t.getNameAsString().endsWith("ImplBase"));
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
