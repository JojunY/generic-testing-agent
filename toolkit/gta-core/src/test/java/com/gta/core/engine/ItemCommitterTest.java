package com.gta.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.change.ChangeChecker;
import com.gta.core.config.GtaPaths;
import com.gta.core.oracle.Lock;
import com.gta.core.oracle.OracleShard;
import com.gta.core.run.TestRunnerFake;
import com.gta.core.state.ItemStatus;
import com.gta.core.state.WorkState;
import com.gta.core.util.AtomicFiles;
import java.nio.file.Files;
import java.nio.file.Path;
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
