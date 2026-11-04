package com.gta.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.config.GtaPaths;
import com.gta.core.oracle.OracleShard;
import com.gta.core.plan.PlanItem;
import com.gta.core.profile.ServiceProfile;
import com.gta.core.run.TestRunnerFake;
import com.gta.core.spec.Spec;
import com.gta.core.spec.SpecParser;
import com.gta.core.state.ItemStatus;
import com.gta.core.state.StateStore;
import com.gta.core.state.WorkState;
import com.gta.core.util.AtomicFiles;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The plan follows the approved spec; tests that already exist are verified, not written again (plan §5.2). */
class PlanSyncTest {
    @TempDir
    Path root;

    GtaPaths paths;
    WorkState st;

    @BeforeEach
    void service() {
        paths = OrderServiceTestData.create(root);
        st = new WorkState();
    }

    private Spec spec() {
        return SpecParser.parse(paths.spec("order.create"));
    }

    private List<PlanItem> sync() {
        return new EngineContext(root, new TestRunnerFake()).planSync.syncPlan(st, spec(), new ServiceProfile());
    }

    @Test
    void shouldPlanTheAnsweredScenariosAsPendingItems() {
        List<PlanItem> items = sync();

        assertEquals(
                List.of("order#foundation-01", OrderServiceTestData.ITEM),
                items.stream().map(i -> i.id).toList());
        assertEquals(ItemStatus.PENDING, st.items.get(OrderServiceTestData.ITEM).status);
        assertEquals(2, new StateStore(paths).loadPlan().size(), "the plan is saved");
    }

    @Test
    void shouldKeepAnUnchangedPassedItemAndCancelARemovedOne() {
        sync();
        st.items.get(OrderServiceTestData.ITEM).status = ItemStatus.PASSED;
        PlanItem gone = OrderServiceTestData.item();
        gone.id = "order.create/s07#component-01";
        gone.ref = "order.create/s07";
        List<PlanItem> plan = new StateStore(paths).loadPlan();
        plan.add(gone);
        new StateStore(paths).savePlan(plan);
        WorkState.ItemState goneState = new WorkState.ItemState();
        goneState.id = gone.id;
        st.items.put(gone.id, goneState);

        sync();

        assertEquals(
                ItemStatus.PASSED, st.items.get(OrderServiceTestData.ITEM).status, "same revision: nothing to redo");
        assertEquals(ItemStatus.CANCELLED, st.items.get(gone.id).status, "its scenario left the spec");
    }

    @Test
    void shouldAdoptACommittedSeedAndALockedScenarioTest() {
        String seed = "src/test/java/com/acme/order/ApplicationIT.java";
        AtomicFiles.write(root.resolve(seed), "class ApplicationIT {}\n");
        AtomicFiles.write(
                root.resolve("src/test/java/com/acme/order/OrderControllerIT.java"), "class OrderControllerIT {}\n");
        OrderServiceTestData.git(root, "add", "-A");
        OrderServiceTestData.git(root, "commit", "-qm", "tests");
        OracleShard.Entry e = new OracleShard.Entry();
        e.scenarioRevision = spec().scenario("order.create/s01").orElseThrow().revision();
        e.tokens = List.of("201");
        e.testClass = OrderServiceTestData.TEST_CLASS;
        OracleShard.write(paths.oracleShard("order.create"), Map.of(OrderServiceTestData.ITEM, e));
        ServiceProfile profile = new ServiceProfile();
        profile.basePackage = "com.acme.order";

        new EngineContext(root, new TestRunnerFake()).planSync.syncPlan(st, spec(), profile);

        assertEquals(
                ItemStatus.VERIFYING, st.items.get("order#foundation-01").status, "the committed seed is verified");
        WorkState.ItemState s01 = st.items.get(OrderServiceTestData.ITEM);
        assertEquals(ItemStatus.VERIFYING, s01.status);
        assertEquals(List.of("201"), s01.lock.tokens, "the lock comes from the committed shard");
        assertTrue(s01.baseline != null);
    }
}
