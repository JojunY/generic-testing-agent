package com.gta.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.config.GtaPaths;
import com.gta.core.run.TestRunnerFake;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Session end (plan §8): the gate against the base branch and the reports. */
class SessionEndTest {
    @TempDir
    Path root;

    GtaPaths paths;

    @BeforeEach
    void service() {
        paths = OrderServiceTestData.create(root);
    }

    private SessionEnd sessionEnd() {
        return new EngineContext(root, new TestRunnerFake()).sessionEnd;
    }

    @Test
    void shouldCompareWithTheRemoteMainBranchFirst() {
        assertEquals("main", sessionEnd().defaultBase());

        OrderServiceTestData.git(root, "update-ref", "refs/remotes/origin/main", "HEAD");
        assertEquals("origin/main", sessionEnd().defaultBase());

        OrderServiceTestData.git(root, "update-ref", "-d", "refs/remotes/origin/main");
        OrderServiceTestData.git(root, "branch", "-m", "main", "trunk");
        assertEquals("HEAD", sessionEnd().defaultBase(), "no conventional base: the current commit");
    }

    @Test
    void shouldGateOnReportsFromCi() throws Exception {
        Path reports = Files.createDirectories(root.resolve("target/surefire-reports"));
        Files.writeString(
                reports.resolve("TEST-x.xml"), "<testsuite><testcase classname=\"x\" name=\"y()\"/></testsuite>");

        assertEquals("pass", sessionEnd().gate(null, List.of(reports)).decision, "nothing was locked on the base yet");
    }

    @Test
    void shouldRenderTheReportAndThePrCommentReadOnly() {
        assertTrue(sessionEnd().report("md").startsWith("# gta report"));
        assertTrue(sessionEnd().report("pr-comment").startsWith("## gta — behaviour & test report"));
        assertTrue(!Files.exists(paths.state()), "reports never write working state");
    }
}
