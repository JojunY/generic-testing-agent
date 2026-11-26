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
import java.util.HashSet;
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
            "Transform",
            "Pipeline",
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

    /** java.util.function types a Spring Cloud Function or Cloud Stream bean is declared with. */
    static final List<String> FUNCTION_TYPES = List.of("Function", "Consumer", "Supplier");

    /** Spring Integration flow bean types (integration). */
    static final List<String> INTEGRATION_FLOW_TYPES = List.of("IntegrationFlow", "StandardIntegrationFlow");

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
        if (paths.module().isEmpty() && !pom.modules.isEmpty() && !Files.isDirectory(paths.mainJava())) {
            // an aggregator has no sources of its own: profiling it would find no flows and say nothing (a Gradle root
            // project with its own sources and an included build-logic or client module is a service)
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
        p.entrypoints.addAll(entrypoints(main, pom.dependencies));
        dropRepeatedDomain(p.entrypoints, flowId -> Files.exists(paths.spec(flowId)));
        p.entrypoints.addAll(migrations(paths.mainResources()));
        p.entrypoints.addAll(processes(paths.mainResources()));
        p.cucumber = cucumber(JavaIndex.of(paths.testJava()), paths.testResources());
        return p;
    }

    /**
     * The service's Cucumber suite: a test class with {@code @IncludeEngines("cucumber")} (JUnit Platform Suite) or
     * {@code @Cucumber}. Its features folder comes from {@code @SelectClasspathResource} or {@code @SelectPackages}, its
     * glue from the {@code cucumber.glue} configuration parameter (or junit-platform.properties); both default to the
     * suite's package. Null when there is no suite: gta then writes JUnit tests.
     */
    static ServiceProfile.Cucumber cucumber(JavaIndex test, Path testResources) {
        for (JavaIndex.Unit u : test.units()) {
            for (ClassOrInterfaceDeclaration c : u.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                boolean suite = c.getAnnotationByName("IncludeEngines")
                                .map(a -> a.toString().contains("\"cucumber\""))
                                .orElse(false)
                        || c.getAnnotationByName("Cucumber").isPresent();
                if (!suite) {
                    continue;
                }
                String pkg = u.cu().getPackageDeclaration()
                        .map(d -> d.getNameAsString())
                        .orElse("");
                ServiceProfile.Cucumber out = new ServiceProfile.Cucumber();
                out.suite = c.getFullyQualifiedName().orElse(c.getNameAsString());
                out.features = c.getAnnotationByName("SelectClasspathResource")
                        .map(Profiler::pathsOf)
                        .filter(v -> !v.isEmpty())
                        .map(v -> v.get(0))
                        .or(() -> c.getAnnotationByName("SelectPackages")
                                .map(Profiler::pathsOf)
                                .filter(v -> !v.isEmpty())
                                .map(v -> v.get(0).replace('.', '/')))
                        .orElse(pkg.replace('.', '/'))
                        .replaceAll("^/+|/+$", "");
                out.glue = c.getAnnotations().stream()
                        .filter(a -> a.getNameAsString().equals("ConfigurationParameter")
                                && (a.toString().contains("GLUE_PROPERTY_NAME")
                                        || a.toString().contains("cucumber.glue")))
                        .flatMap(a -> a.findAll(com.github.javaparser.ast.expr.MemberValuePair.class).stream())
                        .filter(pair -> pair.getNameAsString().equals("value"))
                        .map(pair -> pair.getValue())
                        .filter(Expression::isStringLiteralExpr)
                        .map(v ->
                                v.asStringLiteralExpr().getValue().split(",")[0].strip())
                        .findFirst()
                        .or(() -> gluePropertyOf(testResources))
                        .orElse(pkg);
                return out;
            }
        }
        return null;
    }

    private static java.util.Optional<String> gluePropertyOf(Path testResources) {
        Path props = testResources.resolve("junit-platform.properties");
        if (!Files.isRegularFile(props)) {
            return java.util.Optional.empty();
        }
        java.util.Properties p = new java.util.Properties();
        try (java.io.Reader r = Files.newBufferedReader(props)) {
            p.load(r);
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(p.getProperty("cucumber.glue")).map(g -> g.split(",")[0].strip());
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

    private static final Set<String> NOT_SERVICE_FILES = Set.of(".git", ".github", "target");

    private static boolean anyFileMatches(Path root, String glob) {
        PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        try (Stream<Path> files = Files.walk(root, 8)) {
            // by path segment, so it holds on Windows too; .github is CI (gta's own gate workflow), not the service
            return files.map(root::relativize)
                    .filter(rel -> java.util.stream.StreamSupport.stream(rel.spliterator(), false)
                            .noneMatch(part -> NOT_SERVICE_FILES.contains(part.toString())))
                    .anyMatch(m::matches);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Shortens flow names that repeat their domain ({@code BorrowController#bookBorrow}: {@code borrow.book}, plan
     * §9.4). A flow keeps its long name when it already has a spec under it, or when the short name is taken.
     */
    static void dropRepeatedDomain(
            List<ServiceProfile.EntryPoint> entries, java.util.function.Predicate<String> hasSpec) {
        Set<String> taken = new LinkedHashSet<>();
        entries.forEach(e -> taken.add(e.flowId));
        for (ServiceProfile.EntryPoint e : entries) {
            String shorter = Ids.withoutRepeatedDomain(e.flowId);
            if (!shorter.equals(e.flowId) && !hasSpec.test(e.flowId) && taken.add(shorter)) {
                taken.remove(e.flowId);
                e.flowId = shorter;
            }
        }
    }

    static List<ServiceProfile.EntryPoint> entrypoints(JavaIndex main) {
        return entrypoints(main, Set.of());
    }

    /**
     * Function beans are flows only in a service that binds them: with Spring Cloud Stream they are message handlers
     * ({@code messaging}), with Spring Cloud Function alone they are functions ({@code cloud-function}, e.g. deployed
     * as Cloud Run functions). Elsewhere a {@code @Bean Function} is an ordinary helper bean.
     */
    static String functionCapability(java.util.Collection<String> dependencies) {
        if (dependencies.stream()
                .anyMatch(d -> d.startsWith("spring-cloud-stream")
                        || d.startsWith("spring-cloud-starter-stream")
                        || d.equals("spring-cloud-gcp-pubsub-stream-binder"))) {
            return "messaging";
        }
        if (dependencies.stream()
                .anyMatch(
                        d -> d.startsWith("spring-cloud-function") || d.startsWith("spring-cloud-starter-function"))) {
            return "cloud-function";
        }
        return null;
    }

    static List<ServiceProfile.EntryPoint> entrypoints(JavaIndex main, java.util.Collection<String> dependencies) {
        String functions = functionCapability(dependencies);
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
                            String verb = verbOf(a);
                            if (verb != null) {
                                // a server-sent event stream is tested with a streaming client; a handler returning
                                // Mono/Flux is a reactive (WebFlux) endpoint: both are tested differently
                                String returns = m.getType().asString();
                                String mapping = a.toString();
                                boolean stream = returns.contains("SseEmitter")
                                        || returns.contains("ResponseBodyEmitter")
                                        || returns.contains("ServerSentEvent")
                                        // produces only an event stream; one of several media types is not a stream
                                        || ((mapping.contains("TEXT_EVENT_STREAM")
                                                        || mapping.contains("text/event-stream"))
                                                && !mapping.matches("(?s).*produces\\s*=\\s*\\{[^}]*,.*"));
                                boolean reactive = returns.startsWith("Mono") || returns.startsWith("Flux");
                                e = base(c, fqcn, m, stream ? "websocket" : reactive ? "webflux" : "rest-api", "http");
                                e.httpMethod = verb;
                                List<String> paths = pathsOf(a);
                                e.path = (paths.isEmpty() ? List.of("") : paths)
                                        .stream()
                                                .map(p -> httpPath(classPath, p))
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
                    if (controller
                            && (m.getAnnotationByName("MessageMapping").isPresent()
                                    || m.getAnnotationByName("SubscribeMapping").isPresent())) {
                        // a STOMP handler: tested with a STOMP client against the random port (websocket)
                        String mapping = m.getAnnotationByName("MessageMapping").isPresent()
                                ? "MessageMapping"
                                : "SubscribeMapping";
                        String prefix = c.getAnnotationByName("MessageMapping")
                                .map(Profiler::pathsOf)
                                .filter(p -> !p.isEmpty())
                                .map(p -> p.get(0))
                                .orElse("");
                        List<String> destinations =
                                pathsOf(m.getAnnotationByName(mapping).get());
                        e = base(c, fqcn, m, "websocket", "stomp");
                        e.trigger = "STOMP " + (mapping.equals("SubscribeMapping") ? "subscribe " : "send ")
                                + (destinations.isEmpty() ? prefix : prefix + destinations.get(0));
                        e.risk = mapping.equals("MessageMapping") ? 6 : 3;
                    }
                    if (isWebSocketHandler(c)
                            && List.of("handleTextMessage", "handleMessage", "handleBinaryMessage")
                                    .contains(m.getNameAsString())) {
                        e = base(c, fqcn, m, "websocket", "websocket");
                        e.trigger = "WebSocket " + c.getNameAsString();
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
                    if (isBeamTransform(c)
                            && m.getNameAsString().equals("expand")
                            && m.getParameters().size() == 1) {
                        // a composite PTransform is a flow: run on the DirectRunner with TestPipeline and PAssert
                        e = base(c, fqcn, m, "beam-pipeline", "transform");
                        e.flowId = e.flowId.substring(0, e.flowId.indexOf('.')) + ".transform";
                        e.trigger = "Beam transform " + c.getNameAsString();
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
                    String beanType = m.getAnnotationByName("Bean").isPresent()
                                    && m.getType().isClassOrInterfaceType()
                            ? m.getType().asClassOrInterfaceType().getNameAsString()
                            : "";
                    if (functions != null && FUNCTION_TYPES.contains(beanType)) {
                        // a function bean: Cloud Stream binds it to <name>-in-0 / <name>-out-0 destinations
                        String bean = m.getNameAsString();
                        e = base(c, fqcn, m, functions, "function");
                        e.flowId = holderDomain(c, "Functions", e.flowId) + "." + Ids.kebab(bean);
                        e.trigger = functions.equals("messaging")
                                ? "Spring Cloud Stream "
                                        + switch (beanType) {
                                            case "Supplier" -> bean + "-out-0";
                                            case "Consumer" -> bean + "-in-0";
                                            default -> bean + "-in-0 -> " + bean + "-out-0";
                                        }
                                : "Spring Cloud Function " + bean;
                        e.risk = beanType.equals("Supplier") ? 3 : 6;
                    }
                    if (INTEGRATION_FLOW_TYPES.contains(beanType)) {
                        // a Spring Integration flow: tested by sending to its input channel (plan §7.5)
                        String name = Ids.kebab(m.getNameAsString().replaceAll("(Integration)?Flow$", ""));
                        e = base(c, fqcn, m, "integration", "integration-flow");
                        String domain = holderDomain(c, "IntegrationConfig", e.flowId);
                        e.flowId = domain + "." + (name.isEmpty() || name.equals(domain) ? "flow" : name);
                        e.trigger = "Spring Integration flow " + m.getNameAsString();
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

    /** Spring joins the class and method mappings with a slash ("/api/orders" + "{id}" is "/api/orders/{id}"). */
    static String httpPath(String classPath, String methodPath) {
        String joined = ("/" + classPath + "/" + methodPath).replaceAll("//+", "/");
        return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
    }

    /**
     * Schema migrations as flows (db-migration): each Flyway {@code db/migration/V<version>__<name>.sql} and each
     * Liquibase changelog under {@code db/changelog/}. The flow is {@code db.<name>}; the spec cites the file with
     * {@code ref=file:<path>}.
     */
    static List<ServiceProfile.EntryPoint> migrations(Path mainResources) {
        List<ServiceProfile.EntryPoint> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
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
                    e.flowId = fileFlowId(
                            "db",
                            flowName.toLowerCase(java.util.Locale.ROOT)
                                    .replaceAll("[^a-z0-9]+", "-")
                                    .replaceAll("^-|-$", ""),
                            "migration",
                            seen);
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
        Set<String> seen = new HashSet<>();
        try (Stream<Path> files = Files.walk(mainResources, 6)) {
            for (Path f :
                    files.filter(x -> x.toString().endsWith(".bpmn")).sorted().toList()) {
                String name = String.valueOf(f.getFileName());
                ServiceProfile.EntryPoint e = new ServiceProfile.EntryPoint();
                e.flowId = fileFlowId(
                        "process", Ids.kebab(name.substring(0, name.length() - ".bpmn".length())), "process", seen);
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

    /**
     * {@code <domain>.<name>} for a flow named after a file: a name {@link Ids#FLOW_ID} accepts (it starts with a
     * letter; {@code V1__2fa.sql} or a description in Chinese would not) and unique among the files already seen.
     */
    static String fileFlowId(String domain, String name, String fallback, Set<String> seen) {
        String n = name.isEmpty() ? fallback : name.matches("[a-z].*") ? name : fallback + "-" + name;
        String id = domain + "." + n;
        for (int i = 2; !seen.add(id); i++) {
            id = domain + "." + n + "-" + i;
        }
        return id;
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

    /**
     * The domain of a flow declared as a bean of a holder class ({@code RecallFunctions}, {@code
     * FeeAccrualIntegrationConfig}): the class without the holder suffix. Only these flows drop it, so no other flow's
     * derived name changes.
     */
    static String holderDomain(ClassOrInterfaceDeclaration c, String suffix, String flowId) {
        String name = c.getNameAsString();
        if (name.endsWith(suffix) && name.length() > suffix.length()) {
            return Ids.kebab(name.substring(0, name.length() - suffix.length()));
        }
        return flowId.substring(0, flowId.indexOf('.'));
    }

    /** A composite Beam transform: extends {@code PTransform<InputT, OutputT>}. */
    static boolean isBeamTransform(ClassOrInterfaceDeclaration c) {
        return c.getExtendedTypes().stream().anyMatch(t -> t.getNameAsString().equals("PTransform"));
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

    /** A raw WebSocket handler: extends TextWebSocketHandler, BinaryWebSocketHandler or implements WebSocketHandler. */
    static boolean isWebSocketHandler(ClassOrInterfaceDeclaration c) {
        return c.getExtendedTypes().stream()
                        .anyMatch(t -> List.of(
                                        "TextWebSocketHandler", "BinaryWebSocketHandler", "AbstractWebSocketHandler")
                                .contains(t.getNameAsString()))
                || c.getImplementedTypes().stream()
                        .anyMatch(t -> t.getNameAsString().equals("WebSocketHandler"));
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
    /**
     * The HTTP verb of a mapping annotation: {@code @RequestMapping} takes it from its {@code method} attribute when
     * that names exactly one, as older Spring code writes {@code method = RequestMethod.GET}.
     */
    static String verbOf(AnnotationExpr a) {
        String verb = HTTP.get(a.getNameAsString());
        if ("ANY".equals(verb) && a instanceof NormalAnnotationExpr n) {
            for (MemberValuePair pair : n.getPairs()) {
                Expression v = pair.getValue();
                if (pair.getNameAsString().equals("method")) {
                    List<Expression> values = v.isArrayInitializerExpr()
                            ? v.asArrayInitializerExpr().getValues()
                            : List.of(v);
                    if (values.size() == 1) {
                        String name = values.get(0).toString();
                        return name.substring(name.lastIndexOf('.') + 1);
                    }
                }
            }
        }
        return verb;
    }

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
