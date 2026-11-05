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
    void shouldDetectSecurityAndCachingButAttachThemOnlyFromTheirPhase() throws Exception {
        Files.writeString(
                root.resolve("pom.xml"),
                Files.readString(root.resolve("pom.xml"))
                        .replace(
                                "</dependencies>",
                                "<dependency><artifactId>spring-boot-starter-security</artifactId></dependency>"
                                        + "<dependency><artifactId>caffeine</artifactId></dependency></dependencies>"));

        ServiceProfile p = Profiler.profile(root, CapabilityRegistry.loadBundled(), "auto");

        assertTrue(
                p.explainOnlyCapabilities.containsAll(List.of("security", "caching")),
                p.explainOnlyCapabilities.toString());
        assertTrue(!p.capabilities.contains("security") && !p.capabilities.contains("caching"), "P2 is not active yet");
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
}
