package com.gta.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Every well-known location (plan §5.2, §10.1). */
class GtaPathsTest {
    final GtaPaths paths = new GtaPaths(Path.of("/repo"));

    @Test
    void shouldPlaceFlowFilesNextToEachOther() {
        assertEquals(Path.of("/repo/.gta/specs/order/create.spec.md"), paths.spec("order.create"));
        assertEquals(Path.of("/repo/.gta/specs/order/create.oracles.json"), paths.oracleShard("order.create"));
        assertEquals(Path.of("/repo/.gta/specs/order/create.cases.md"), paths.cases("order.create"));
        assertEquals(Path.of("/repo/.gta/specs/INDEX.md"), paths.casesIndex());
        assertEquals(Path.of("/repo/.gta/reports"), paths.reports());
    }

    @Test
    void shouldKeepTemporaryFilesUnderWork() {
        for (Path p : new Path[] {
            paths.state(),
            paths.plan(),
            paths.briefs(),
            paths.runs(),
            paths.snapshots(),
            paths.recover(),
            paths.commitLedger(),
            paths.lock()
        }) {
            assertEquals(paths.work(), p.getParent(), p.toString());
        }
    }

    @Test
    void shouldMapFilesBackToFlowIds() {
        assertEquals(Optional.of("order.create"), GtaPaths.flowIdOfSpecPath(".gta/specs/order/create.spec.md"));
        assertEquals(
                Optional.of("order.create"), GtaPaths.flowIdOfSpecPath("svc\\.gta\\specs\\order\\create.oracles.json"));
        assertEquals(Optional.of("order.create"), GtaPaths.flowIdOfCasesPath(".gta/specs/order/create.cases.md"));
        assertEquals(Optional.empty(), GtaPaths.flowIdOfSpecPath(".gta/specs/INDEX.md"));
        assertEquals(Optional.empty(), GtaPaths.flowIdOfCasesPath("docs/create.cases.md"));
    }

    @Test
    void shouldMirrorTestClassesIntoTheTestSourceRoot() {
        assertEquals(
                "src/test/java/com/acme/OrderControllerIT.java", GtaPaths.testSourceRel("com.acme.OrderControllerIT"));
        assertEquals(
                Path.of("/repo/src/test/java/com/acme/OrderControllerIT.java"),
                paths.testSource("com.acme.OrderControllerIT"));
        assertEquals("src/main/java/A.java", paths.rel(Path.of("/repo/src/main/java/A.java")));
    }
}
