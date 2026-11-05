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
}
