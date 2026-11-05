package com.gta.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.config.GtaPaths;
import com.gta.core.run.TestRunnerFake;
import com.gta.core.spec.SpecParser;
import com.gta.core.spec.SpecTestData;
import com.gta.core.state.WorkState;
import com.gta.core.util.ProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** PR mode (plan §6): the diff picks the flows, their specs go stale, the PR description is documented intent. */
class PrModeTest {
    @TempDir
    Path root;

    GtaPaths paths;
    String base;

    private ProcessRunner.Result git(String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return ProcessRunner.run(root, Duration.ofSeconds(30), Map.of(), cmd);
    }

    @BeforeEach
    void approvedFlowOnMain() throws Exception {
        paths = new GtaPaths(root);
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Files.writeString(root.resolve(".gitignore"), ".gta/work/\n");
        Path controller = root.resolve("src/main/java/com/acme/order/OrderController.java");
        Files.createDirectories(controller.getParent());
        Files.writeString(controller, "package com.acme.order;\nclass OrderController {}\n");
        Files.createDirectories(paths.spec("order.create").getParent());
        Files.writeString(
                paths.spec("order.create"),
                SpecTestData.CREATE_ORDER.replace("status=draft", "status=approved rev=R2"));
        git("init", "-q");
        git("config", "user.email", "t@example.com");
        git("config", "user.name", "t");
        git("add", "-A");
        git("commit", "-qm", "init");
        base = git("rev-parse", "HEAD").output().strip();
    }

    @Test
    void shouldWorkOnTheFlowsTheDiffChangesWithThePrDescriptionAsIntent() throws Exception {
        Files.writeString(
                root.resolve("src/main/java/com/acme/order/OrderController.java"),
                "package com.acme.order;\nclass OrderController { int approvalLimit = 1000; }\n");

        Map<String, Object> out = new Engine(root, new TestRunnerFake())
                .next("pr", null, base, false, "Orders over 1000 now need a manager's approval");

        assertEquals("analyze", out.get("node"), out.toString());
        assertEquals("order.create", out.get("flowId"));
        assertEquals("stale", SpecParser.parse(paths.spec("order.create")).status(), "the approved spec is re-checked");
        assertEquals(List.of("order.create"), ((Map<?, ?>) out.get("pr")).get("flows"));
        String brief = Files.readString(root.resolve(String.valueOf(out.get("brief"))));
        assertTrue(
                brief.contains("Orders over 1000 now need a manager's approval"), "the brief quotes the PR: " + brief);
        assertTrue(brief.contains("gta-analyzing-change-impact"), brief);
    }

    @Test
    void shouldSaySoWhenTheDiffAffectsNoFlow() throws Exception {
        Files.writeString(root.resolve("README.md"), "docs only\n");

        Map<String, Object> out = new Engine(root, new TestRunnerFake()).next("pr", null, base, false, null);

        assertEquals("done", out.get("node"), out.toString());
        assertTrue(String.valueOf(out.get("message")).contains("No flow is affected"), out.toString());
        assertEquals("approved", SpecParser.parse(paths.spec("order.create")).status());
    }

    @Test
    void shouldMoveToTheNextQueuedFlowOnlyInPrMode() {
        WorkState st = new WorkState();
        st.prQueue = new ArrayList<>(List.of("order.cancel"));
        assertNull(PrMode.advance(st), "flow mode never jumps to another flow");
        st.mode = "pr";
        assertEquals("order.cancel", PrMode.advance(st));
        assertNull(PrMode.advance(st));
    }
}
