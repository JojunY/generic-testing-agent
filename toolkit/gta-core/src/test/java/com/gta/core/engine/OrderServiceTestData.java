package com.gta.core.engine;

import com.gta.core.config.GtaPaths;
import com.gta.core.plan.PlanItem;
import com.gta.core.spec.SpecTestData;
import com.gta.core.state.StateStore;
import com.gta.core.util.AtomicFiles;
import com.gta.core.util.ProcessRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A small service repository for engine tests: git, an approved order.create spec and its planned s01 item. */
final class OrderServiceTestData {
    static final String ITEM = "order.create/s01#component-01";
    static final String TEST_CLASS = "com.acme.order.OrderControllerIT";

    private OrderServiceTestData() {}

    static ProcessRunner.Result git(Path root, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return ProcessRunner.run(root, Duration.ofSeconds(30), Map.of(), cmd);
    }

    /** Creates the repository with one commit on branch main. */
    static GtaPaths create(Path root) {
        GtaPaths paths = new GtaPaths(root);
        AtomicFiles.write(root.resolve("pom.xml"), "<project/>\n");
        AtomicFiles.write(root.resolve(".gitignore"), ".gta/work/\n");
        AtomicFiles.write(
                root.resolve("src/main/java/com/acme/order/OrderController.java"),
                "package com.acme.order;\npublic class OrderController { public Object create() { return null; } }\n");
        AtomicFiles.write(
                paths.spec("order.create"),
                SpecTestData.CREATE_ORDER.replace("status=draft", "status=approved rev=R2"));
        git(root, "init", "-q", "-b", "main");
        git(root, "config", "user.email", "t@example.com");
        git(root, "config", "user.name", "t");
        git(root, "add", "-A");
        git(root, "commit", "-qm", "service");
        return paths;
    }

    static PlanItem item() {
        PlanItem i = new PlanItem();
        i.id = ITEM;
        i.kind = "test";
        i.ref = "order.create/s01";
        i.flowId = "order.create";
        i.title = "valid order";
        i.layer = "component";
        i.testClass = TEST_CLASS;
        i.ownsFiles.add("src/test/java/com/acme/order/OrderControllerIT.java");
        i.ownsFiles.add("pom.xml");
        i.oracle.source = "observed";
        i.oracle.then = new ArrayList<>(List.of("201", "50.00"));
        return i;
    }

    static void plan(GtaPaths paths, PlanItem... items) {
        new StateStore(paths).savePlan(new ArrayList<>(List.of(items)));
    }
}
