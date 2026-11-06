package com.gta.core.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.registry.CapabilityRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Code profiling (plan §6.1): entry points, flow ids, base package and capabilities of a service. */
class ProfilerTest {
    @TempDir
    Path root;

    private void source(String rel, String code) throws Exception {
        Path f = root.resolve("src/main/java").resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, code);
    }

    @BeforeEach
    void service() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>4.1.0</version></parent>
                  <artifactId>order-service</artifactId>
                  <dependencies>
                    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
                    <dependency><groupId>org.springframework.kafka</groupId><artifactId>spring-kafka</artifactId></dependency>
                  </dependencies>
                </project>
                """);
        source("com/acme/order/OrderApplication.java", """
                package com.acme.order;
                @SpringBootApplication
                public class OrderApplication {}
                """);
        source("com/acme/order/api/OrderController.java", """
                package com.acme.order.api;
                @RestController
                @RequestMapping("/api/orders")
                public class OrderController {
                    @PostMapping
                    public Object create() { return null; }
                    @GetMapping({"/{id}", "/by-id/{id}"})
                    public Object find() { return null; }
                    public void helper() {}
                }
                """);
        source("com/acme/order/job/CleanupJob.java", """
                package com.acme.order.job;
                public class CleanupJob {
                    @Scheduled(cron = "0 0 * * * *")
                    public void run() {}
                }
                """);
        source("com/acme/order/messaging/PaymentListener.java", """
                package com.acme.order.messaging;
                public class PaymentListener {
                    @KafkaListener(topics = "payments")
                    public void onPayment(String event) {}
                }
                """);
    }

    private Map<String, ServiceProfile.EntryPoint> byFlowId(ServiceProfile p) {
        return p.entrypoints.stream().collect(Collectors.toMap(e -> e.flowId, e -> e));
    }

    @Test
    void shouldFindEveryKindOfEntryPoint() {
        ServiceProfile p = Profiler.profile(root, CapabilityRegistry.loadBundled(), "auto");
        Map<String, ServiceProfile.EntryPoint> flows = byFlowId(p);

        assertEquals(
                List.of("order.create", "order.find", "cleanup.run", "payment.on-payment"),
                p.entrypoints.stream().map(e -> e.flowId).toList());
        ServiceProfile.EntryPoint create = flows.get("order.create");
        assertEquals("rest-api", create.capability);
        assertEquals("POST", create.httpMethod);
        assertEquals("/api/orders", create.path);
        assertEquals("method:com.acme.order.api.OrderController#create", create.ref);
        assertEquals("scheduler", flows.get("cleanup.run").capability);
        assertEquals("messaging", flows.get("payment.on-payment").capability);
    }

    @Test
    void shouldKeepEveryPathOfAMapping() {
        ServiceProfile.EntryPoint find = byFlowId(Profiler.profile(root, CapabilityRegistry.loadBundled(), "auto"))
                .get("order.find");

        assertEquals("/api/orders/{id} | /api/orders/by-id/{id}", find.path);
        assertEquals(2, find.risk, "reads are lower risk than writes");
    }

    @Test
    void shouldTakeTheBasePackageFromTheApplicationClassOrTheConfiguration() {
        assertEquals("com.acme.order", Profiler.profile(root, CapabilityRegistry.loadBundled(), "auto").basePackage);
        assertEquals("com.acme", Profiler.profile(root, CapabilityRegistry.loadBundled(), "com.acme").basePackage);
    }

    @Test
    void shouldFallBackToTheCommonPackageWithoutAnApplicationClass() throws Exception {
        Files.delete(root.resolve("src/main/java/com/acme/order/OrderApplication.java"));

        assertEquals("com.acme.order", Profiler.profile(root, CapabilityRegistry.loadBundled(), "auto").basePackage);
    }

    @Test
    void shouldReadTheBuildFromThePom() {
        ServiceProfile p = Profiler.profile(root, CapabilityRegistry.loadBundled(), "auto");

        assertEquals("order-service", p.artifactId);
        assertEquals(4, p.bootMajor);
        assertTrue(p.capabilities.contains("rest-api"), p.capabilities.toString());
        assertTrue(p.dependencies.contains("spring-kafka"));
    }

    @Test
    void shouldGiveFlowsOfTheSameNameDistinctIds() throws Exception {
        source("com/acme/order/api/v2/OrderController.java", """
                package com.acme.order.api.v2;
                @RestController
                public class OrderController {
                    @PostMapping("/v2/orders")
                    public Object create() { return null; }
                }
                """);

        List<String> ids = Profiler.profile(root, CapabilityRegistry.loadBundled(), "auto").entrypoints.stream()
                .map(e -> e.flowId)
                .toList();

        assertTrue(ids.contains("order.create") && ids.contains("order.create-2"), ids.toString());
    }

    @Test
    void shouldProfileTheConfiguredModuleOfAMultiModuleRepository() throws Exception {
        Path repo = root.resolve("repo");
        Path module = repo.resolve("order-api");
        Files.createDirectories(module);
        Files.writeString(
                repo.resolve("pom.xml"),
                "<project><artifactId>orders</artifactId><modules><module>order-api</module></modules></project>");
        Files.move(root.resolve("pom.xml"), module.resolve("pom.xml"));
        Files.move(root.resolve("src"), module.resolve("src"));

        org.junit.jupiter.api.Assertions.assertThrows(
                com.gta.core.error.GtaException.class,
                () -> Profiler.profile(repo, CapabilityRegistry.loadBundled(), "auto"),
                "an aggregator without build.module must not read as a service with no flows");

        ServiceProfile p = Profiler.profile(
                new com.gta.core.config.GtaPaths(repo, "order-api"), CapabilityRegistry.loadBundled(), "auto");
        assertEquals("order-api", p.module);
        assertEquals("com.acme.order", p.basePackage);
        assertTrue(
                p.entrypoints.stream().anyMatch(e -> e.ref.contains("OrderController#create")),
                p.entrypoints.toString());
    }

    @Test
    void shouldFindRabbitAndSqsListenersAndQuartzJobs() throws Exception {
        source("com/acme/order/messaging/RefundListener.java", """
                package com.acme.order.messaging;
                public class RefundListener {
                    @RabbitListener(queues = "refunds")
                    public void onRefund(String event) {}
                    @SqsListener("returns")
                    public void onReturn(String event) {}
                }
                """);
        source("com/acme/order/job/ReportJob.java", """
                package com.acme.order.job;
                public class ReportJob extends QuartzJobBean {
                    protected void executeInternal(JobExecutionContext context) {}
                    void helper() {}
                }
                """);

        Map<String, ServiceProfile.EntryPoint> flows =
                byFlowId(Profiler.profile(root, CapabilityRegistry.loadBundled(), "auto"));

        assertEquals("messaging", flows.get("refund.on-refund").capability);
        assertEquals("messaging", flows.get("refund.on-return").capability);
        ServiceProfile.EntryPoint quartz = flows.get("report.execute-internal");
        assertEquals("scheduler", quartz.capability, flows.keySet().toString());
        assertTrue(quartz.trigger.contains("Quartz"), quartz.trigger);
        assertTrue(flows.keySet().stream().noneMatch(f -> f.endsWith(".helper")), "only the job method is a flow");
    }

    @Test
    void shouldDetectSecurityAndCachingAsCrossCuttingCapabilities() throws Exception {
        Files.writeString(
                root.resolve("pom.xml"),
                Files.readString(root.resolve("pom.xml"))
                        .replace(
                                "</dependencies>",
                                "<dependency><artifactId>spring-boot-starter-security</artifactId></dependency>"
                                        + "<dependency><artifactId>caffeine</artifactId></dependency></dependencies>"));

        ServiceProfile p = Profiler.profile(root, CapabilityRegistry.loadBundled(), "auto");

        assertTrue(p.capabilities.containsAll(List.of("security", "caching")), p.capabilities.toString());
        assertTrue(
                p.entrypoints.stream()
                        .noneMatch(e -> "security".equals(e.capability) || "caching".equals(e.capability)),
                "cross-cutting capabilities add skills to flows, they are never flows themselves");
    }

    @Test
    void shouldFindSpringBatchJobBeans() throws Exception {
        source("com/acme/order/batch/ImportConfig.java", """
                package com.acme.order.batch;
                @Configuration
                public class ImportConfig {
                    @Bean
                    public Job importOrders(JobRepository jobs, Step load) { return null; }
                    @Bean
                    public Step load(JobRepository jobs) { return null; }
                }
                """);

        Map<String, ServiceProfile.EntryPoint> flows =
                byFlowId(Profiler.profile(root, CapabilityRegistry.loadBundled(), "auto"));

        assertEquals(
                "spring-batch",
                flows.get("import.import-orders").capability,
                flows.keySet().toString());
        assertTrue(
                flows.keySet().stream().noneMatch(f -> f.endsWith(".load")), "a Step is part of its job, not a flow");
    }

    @Test
    void shouldTurnEachMigrationIntoAFlow() throws Exception {
        Path migrations = root.resolve("src/main/resources/db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__customers.sql"), "create table customer (id int);");
        Files.writeString(migrations.resolve("V2_1__Customer_Tier.sql"), "alter table customer add tier int;");
        Files.writeString(migrations.resolve("notes.txt"), "not a migration");
        Path changelog = root.resolve("src/main/resources/db/changelog");
        Files.createDirectories(changelog);
        Files.writeString(changelog.resolve("add-orders.yaml"), "databaseChangeLog: []");

        Map<String, ServiceProfile.EntryPoint> flows =
                byFlowId(Profiler.profile(root, CapabilityRegistry.loadBundled(), "auto"));

        ServiceProfile.EntryPoint tier = flows.get("db.customer-tier");
        assertEquals("db-migration", tier.capability, flows.keySet().toString());
        assertEquals("file:src/main/resources/db/migration/V2_1__Customer_Tier.sql", tier.ref);
        assertEquals("Flyway V2_1", tier.trigger);
        assertEquals("db-migration", flows.get("db.add-orders").capability, "Liquibase changelogs too");
        assertTrue(flows.containsKey("db.customers"));
        assertTrue(flows.keySet().stream().noneMatch(f -> f.contains("notes")));
    }

    @Test
    void shouldTellReactiveEndpointsAndGraphqlHandlersApart() throws Exception {
        source("com/acme/order/api/StockController.java", """
                package com.acme.order.api;
                @RestController
                @RequestMapping("/api/stock")
                public class StockController {
                    @PostMapping("/{id}/reserve")
                    public Mono<Stock> reserve() { return null; }
                    @GetMapping
                    public Flux<Stock> all() { return null; }
                }
                """);
        source("com/acme/order/graphql/OrderGraphqlController.java", """
                package com.acme.order.graphql;
                @Controller
                public class OrderGraphqlController {
                    @QueryMapping
                    public Order order(String id) { return null; }
                    @MutationMapping
                    public Order cancel(String id) { return null; }
                }
                """);

        Map<String, ServiceProfile.EntryPoint> flows =
                byFlowId(Profiler.profile(root, CapabilityRegistry.loadBundled(), "auto"));

        assertEquals("webflux", flows.get("stock.reserve").capability, "a Mono handler is reactive");
        assertEquals("webflux", flows.get("stock.all").capability);
        assertEquals("rest-api", flows.get("order.create").capability, "a plain handler stays rest-api");
        assertEquals(
                "graphql-api",
                flows.get("order-graphql.order").capability,
                flows.keySet().toString());
        assertEquals("GraphQL mutation cancel", flows.get("order-graphql.cancel").trigger);
        assertEquals(6, flows.get("order-graphql.cancel").risk, "a mutation is riskier than a query");
    }

    @Test
    void shouldTurnStompHandlersAndWebSocketHandlersIntoWebsocketFlows() throws Exception {
        source("com/acme/order/ws/PriceController.java", """
                package com.acme.order.ws;
                @Controller
                @MessageMapping("/prices")
                public class PriceController {
                    @MessageMapping("/update")
                    @SendTo("/topic/prices")
                    public PriceUpdate update(PriceUpdate update) { return update; }
                    @SubscribeMapping("/latest")
                    public PriceUpdate latest() { return null; }
                    public void helper() {}
                }
                """);
        source("com/acme/order/ws/EchoHandler.java", """
                package com.acme.order.ws;
                public class EchoHandler extends TextWebSocketHandler {
                    @Override
                    protected void handleTextMessage(WebSocketSession session, TextMessage message) {}
                    private void log() {}
                }
                """);

        Map<String, ServiceProfile.EntryPoint> flows =
                byFlowId(Profiler.profile(root, CapabilityRegistry.loadBundled(), "auto"));

        ServiceProfile.EntryPoint update = flows.get("price.update");
        assertEquals("websocket", update.capability, flows.keySet().toString());
        assertEquals("STOMP send /prices/update", update.trigger, "the class prefix comes first");
        assertEquals(6, update.risk);
        assertEquals("STOMP subscribe /prices/latest", flows.get("price.latest").trigger);
        assertEquals(3, flows.get("price.latest").risk, "a subscription only reads");
        assertTrue(!flows.containsKey("price.helper"), "a method without a mapping is no flow");
        assertEquals(
                "websocket",
                flows.values().stream()
                        .filter(e -> "WebSocket EchoHandler".equals(e.trigger))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(flows.keySet().toString()))
                        .capability);
    }

    @Test
    void shouldTurnEachRpcOfAGrpcServiceIntoAFlow() throws Exception {
        source("com/acme/order/grpc/StockGrpcService.java", """
                package com.acme.order.grpc;
                @Service
                public class StockGrpcService extends StockServiceGrpc.StockServiceImplBase {
                    @Override
                    public void getStock(StockRequest request, StreamObserver<StockReply> reply) {}
                    private void helper(StreamObserver<StockReply> reply) {}
                }
                """);

        Map<String, ServiceProfile.EntryPoint> flows =
                byFlowId(Profiler.profile(root, CapabilityRegistry.loadBundled(), "auto"));

        ServiceProfile.EntryPoint rpc = flows.get("stock.get-stock");
        assertEquals("grpc-api", rpc.capability, flows.keySet().toString());
        assertEquals("gRPC StockService.getStock", rpc.trigger);
        assertTrue(flows.keySet().stream().noneMatch(f -> f.endsWith(".helper")), "only overridden RPCs are flows");
    }

    @Test
    void shouldTurnTemporalWorkflowsStateMachinesAndBpmnProcessesIntoFlows() throws Exception {
        source("com/acme/order/flow/RestockWorkflow.java", """
                package com.acme.order.flow;
                @WorkflowInterface
                public interface RestockWorkflow {
                    @WorkflowMethod
                    String restock(String productId, int quantity);
                    @SignalMethod
                    void pause();
                }
                """);
        source("com/acme/order/flow/RestockWorkflowImpl.java", """
                package com.acme.order.flow;
                public class RestockWorkflowImpl implements RestockWorkflow {
                    public String restock(String productId, int quantity) { return "restocked"; }
                    public void pause() {}
                }
                """);
        source("com/acme/order/flow/OrderStateMachineConfig.java", """
                package com.acme.order.flow;
                @Configuration
                @EnableStateMachine
                public class OrderStateMachineConfig extends EnumStateMachineConfigurerAdapter<States, Events> {
                    @Override
                    public void configure(StateMachineStateConfigurer<States, Events> states) {}
                    @Override
                    public void configure(StateMachineTransitionConfigurer<States, Events> transitions) {}
                }
                """);
        Path bpmn = root.resolve("src/main/resources/processes");
        Files.createDirectories(bpmn);
        Files.writeString(bpmn.resolve("Order_Approval.bpmn"), "<definitions/>");

        Map<String, ServiceProfile.EntryPoint> flows =
                byFlowId(Profiler.profile(root, CapabilityRegistry.loadBundled(), "auto"));

        ServiceProfile.EntryPoint temporal = flows.get("restock.restock");
        assertEquals("workflow", temporal.capability, flows.keySet().toString());
        assertEquals("method:com.acme.order.flow.RestockWorkflowImpl#restock", temporal.ref, "the implementation");
        assertTrue(!flows.containsKey("restock.pause"), "a signal is part of the workflow, not a flow of its own");
        ServiceProfile.EntryPoint machine = flows.get("order.transitions");
        assertEquals("workflow", machine.capability, flows.keySet().toString());
        assertEquals("Spring Statemachine OrderStateMachineConfig", machine.trigger);
        ServiceProfile.EntryPoint process = flows.get("process.order-approval");
        assertEquals(
                "file:src/main/resources/processes/Order_Approval.bpmn",
                process.ref,
                flows.keySet().toString());
        assertEquals("workflow", process.capability);
    }

    @Test
    void shouldTurnEachCompositeBeamTransformIntoAFlow() throws Exception {
        source("com/acme/order/report/LowStockTransform.java", """
                package com.acme.order.report;
                public class LowStockTransform extends PTransform<PCollection<KV<String, Integer>>, PCollection<String>> {
                    @Override
                    public PCollection<String> expand(PCollection<KV<String, Integer>> stock) { return null; }
                    static boolean low(int stock) { return stock < 3; }
                }
                """);

        Map<String, ServiceProfile.EntryPoint> flows =
                byFlowId(Profiler.profile(root, CapabilityRegistry.loadBundled(), "auto"));

        ServiceProfile.EntryPoint transform = flows.get("low-stock.transform");
        assertEquals("beam-pipeline", transform.capability, flows.keySet().toString());
        assertEquals("method:com.acme.order.report.LowStockTransform#expand", transform.ref);
        assertTrue(flows.keySet().stream().noneMatch(f -> f.endsWith(".low")), "helpers are not flows");
    }
}
