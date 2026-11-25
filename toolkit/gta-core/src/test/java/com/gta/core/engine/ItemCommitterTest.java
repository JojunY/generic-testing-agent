package com.gta.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.change.ChangeChecker;
import com.gta.core.config.GtaPaths;
import com.gta.core.oracle.Lock;
import com.gta.core.oracle.OracleShard;
import com.gta.core.plan.PlanItem;
import com.gta.core.run.TestRunnerFake;
import com.gta.core.state.ItemStatus;
import com.gta.core.state.WorkState;
import com.gta.core.util.AtomicFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Item commits (plan §5.4): the test with its oracle lock, never the developer's own uncommitted edits. */
class ItemCommitterTest {
    @TempDir
    Path root;

    GtaPaths paths;
    EngineContext ctx;

    @BeforeEach
    void service() {
        paths = OrderServiceTestData.create(root);
        OrderServiceTestData.plan(paths, OrderServiceTestData.item());
        ctx = new EngineContext(root, new TestRunnerFake());
    }

    @Test
    void shouldCommitTheTestAndTheLockButNotTheDevelopersOwnEdits() throws Exception {
        AtomicFiles.write(root.resolve("pom.xml"), "<project><!-- developer's edit --></project>\n");
        WorkState.ItemState s = new WorkState.ItemState();
        s.id = OrderServiceTestData.ITEM;
        s.status = ItemStatus.PASSED;
        s.baseline = new ChangeChecker(paths).capture();
        s.lock = new Lock();
        s.lock.tokens.add("201");
        AtomicFiles.write(
                root.resolve("src/test/java/com/acme/order/OrderControllerIT.java"), "class OrderControllerIT {}\n");
        // the item also edits the developer's dirty pom (e.g. a test dependency): it holds both edits now
        AtomicFiles.write(root.resolve("pom.xml"), "<project><!-- developer's edit --><!-- gta-junit --></project>\n");

        ctx.committer.commitItem(new WorkState(), OrderServiceTestData.item(), s, "r1", "valid order");

        String committed = OrderServiceTestData.git(root, "show", "--name-only", "--format=", "HEAD")
                .output();
        assertTrue(committed.contains("src/test/java/com/acme/order/OrderControllerIT.java"), committed);
        assertTrue(committed.contains(".gta/specs/order/create.oracles.json"), committed);
        assertFalse(committed.contains("pom.xml"), "the developer's edit stays theirs");
        assertTrue(s.reason.contains("pom.xml"), s.reason);
        assertEquals(1, OracleShard.read(paths.oracleShard("order.create")).size());
        assertTrue(new com.gta.core.change.CommitLedger(paths).contains(s.commit), "recorded in the ledger");
    }

    @Test
    void shouldKeepAHumansRelockRecordWhenTheLockedAssertionsDidNotChange() throws Exception {
        WorkState.ItemState s = new WorkState.ItemState();
        s.id = OrderServiceTestData.ITEM;
        s.status = ItemStatus.PASSED;
        s.baseline = new ChangeChecker(paths).capture();
        s.lock = new Lock();
        s.lock.tokens.add("201");
        AtomicFiles.write(
                root.resolve("src/test/java/com/acme/order/OrderControllerIT.java"), "class OrderControllerIT {}\n");
        OracleShard.Entry relocked = new OracleShard.Entry();
        relocked.fingerprint = com.gta.core.oracle.OracleLock.fingerprint(List.of()); // what the test above locks
        relocked.relockedBy = "alice@corp";
        relocked.reason = "assertion style changed";
        relocked.relockedAt = "2026-11-20T10:00:00Z";
        OracleShard.write(paths.oracleShard("order.create"), java.util.Map.of(OrderServiceTestData.ITEM, relocked));

        ctx.committer.commitItem(new WorkState(), OrderServiceTestData.item(), s, "r2", "repaired");

        OracleShard.Entry now =
                OracleShard.read(paths.oracleShard("order.create")).get(OrderServiceTestData.ITEM);
        assertEquals("alice@corp", now.relockedBy, "the gate still sees who relocked");
        assertEquals("2026-11-20T10:00:00Z", now.relockedAt);
    }

    @Test
    void shouldCommitItsOwnTestWhenItRanAgainUnchanged() throws Exception {
        // written in an earlier attempt; the retry's baseline already holds the uncommitted test
        String test = "src/test/java/com/acme/order/OrderControllerIT.java";
        AtomicFiles.write(root.resolve(test), "class OrderControllerIT {}\n");
        WorkState.ItemState s = new WorkState.ItemState();
        s.id = OrderServiceTestData.ITEM;
        s.status = ItemStatus.PASSED;
        s.written.add(test);
        s.baseline = new ChangeChecker(paths).capture();
        s.lock = new Lock();
        s.lock.tokens.add("201");

        ctx.committer.commitItem(new WorkState(), OrderServiceTestData.item(), s, "r1", "valid order");

        String committed = OrderServiceTestData.git(root, "show", "--name-only", "--format=", "HEAD")
                .output();
        assertTrue(committed.contains(test), committed);
        assertTrue(s.written.isEmpty(), "committed: nothing of its own is left");
    }

    @Test
    void shouldStillCommitTheTestAnItemWroteBeforeItWasSentBack() throws Exception {
        PlanItem item = OrderServiceTestData.item();
        item.ownsFiles.add("src/test/resources/fixtures/order/**");
        String test = "src/test/java/com/acme/order/OrderControllerIT.java";
        String fixture = "src/test/resources/fixtures/order/s01.json";
        AtomicFiles.write(root.resolve(test), "class OrderControllerIT {}\n");
        AtomicFiles.write(root.resolve(fixture), "{}\n");
        WorkState.ItemState s = new WorkState.ItemState();
        s.id = OrderServiceTestData.ITEM;
        s.status = ItemStatus.PASSED;
        s.written.addAll(List.of(test, fixture)); // its earlier submit
        // a person confirmed a defect: the item goes back to its author with a new baseline, the author pins the test
        s.baseline = new ChangeChecker(paths).capture();
        AtomicFiles.write(root.resolve(test), "@KnownDefect class OrderControllerIT {}\n");

        ctx.committer.commitItem(new WorkState(), item, s, "r2", "pin known defect");

        String committed = OrderServiceTestData.git(root, "show", "--name-only", "--format=", "HEAD")
                .output();
        assertTrue(committed.contains(fixture), committed);
        assertTrue(committed.contains("OrderControllerIT.java"), committed);
    }

    @Test
    void shouldNotCommitAnUncommittedEditTheItemDidNotWrite() throws Exception {
        String test = "src/test/java/com/acme/order/OrderControllerIT.java";
        AtomicFiles.write(root.resolve(test), "class OrderControllerIT { /* someone else's edit */ }\n");
        WorkState.ItemState s = new WorkState.ItemState();
        s.id = OrderServiceTestData.ITEM;
        s.status = ItemStatus.PASSED;
        s.baseline = new ChangeChecker(paths).capture();
        s.lock = new Lock();
        s.lock.tokens.add("201");

        ctx.committer.commitItem(new WorkState(), OrderServiceTestData.item(), s, "r1", "valid order");

        String committed = OrderServiceTestData.git(root, "show", "--name-only", "--format=", "HEAD")
                .output();
        assertFalse(committed.contains(test), committed);
    }

    @Test
    void shouldNotCommitTheDevelopersOwnEditsInATestClassGtaShares() throws Exception {
        Path test = root.resolve("src/test/java/com/acme/order/OrderControllerIT.java");
        AtomicFiles.write(test, "class OrderControllerIT {}\n");
        OrderServiceTestData.git(root, "add", "-A");
        OrderServiceTestData.git(root, "commit", "-qm", "developer's test class");
        AtomicFiles.write(test, "class OrderControllerIT { /* developer's work in progress */ }\n");
        WorkState.ItemState s = new WorkState.ItemState();
        s.id = OrderServiceTestData.ITEM;
        s.status = ItemStatus.PASSED;
        s.sharesDeveloperFile = true; // naming conflict answered "add gta's tests to it"
        s.baseline = new ChangeChecker(paths).capture();
        AtomicFiles.write(test, "class OrderControllerIT { /* developer's work in progress */ void gtaTest() {} }\n");

        ctx.committer.commitItem(new WorkState(), OrderServiceTestData.item(), s, "r1", "valid order");

        assertTrue(Files.readString(test).contains("work in progress"), "the file is left as it is");
        assertTrue(s.reason.contains("OrderControllerIT.java"), "the person commits it: " + s.reason);
        assertFalse(
                OrderServiceTestData.git(root, "status", "--porcelain", "--", "src/test")
                        .output()
                        .isBlank(),
                "never committed on the developer's behalf");
    }

    @Test
    void shouldTrustOnlyACommittedDefectRecord() throws Exception {
        Path record = paths.escalations().resolve("order.create__s01__component-01.md");
        AtomicFiles.write(record, EscalationWriter.DEFECT_FOUND_MARKER + "\n");
        assertFalse(ctx.committer.confirmedPin(OrderServiceTestData.item()), "the working tree does not count");

        OrderServiceTestData.git(root, "add", "-A");
        OrderServiceTestData.git(root, "commit", "-qm", "record");

        assertTrue(ctx.committer.confirmedPin(OrderServiceTestData.item()));
        assertTrue(Files.exists(record));
    }
}
