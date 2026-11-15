package com.gta.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Surefire XML merged with gta-junit scenario tags (plan §5.3: results map to scenarios by tag). */
class SurefireReportParserTest {
    @TempDir
    Path dir;

    Path reports;
    Path gtaResults;

    @BeforeEach
    void dirs() throws Exception {
        reports = Files.createDirectories(dir.resolve("surefire-reports"));
        gtaResults = Files.createDirectories(dir.resolve("gta-results"));
    }

    private void report(String name, String testcases) throws Exception {
        Files.writeString(
                reports.resolve("TEST-" + name + ".xml"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"" + name + "\">\n" + testcases
                        + "</testsuite>\n");
    }

    private void tags(String lines) throws Exception {
        Files.writeString(gtaResults.resolve("results.jsonl"), lines);
    }

    @Test
    void shouldReadOutcomesFromSurefireXml() throws Exception {
        report("com.acme.OrderIT", """
                <testcase classname="com.acme.OrderIT" name="passes()"/>
                <testcase classname="com.acme.OrderIT" name="fails()"><failure type="org.opentest4j.AssertionFailedError" message="expected: &lt;201&gt; but was: &lt;500&gt;">stack</failure></testcase>
                <testcase classname="com.acme.OrderIT" name="breaks()"><error type="java.lang.IllegalStateException" message="boom">stack</error></testcase>
                <testcase classname="com.acme.OrderIT" name="skipped()"><skipped/></testcase>
                """);

        List<TestOutcome> cases = SurefireReportParser.parse(List.of(reports), gtaResults);

        assertEquals(
                List.of("passed", "failed", "error", "skipped"),
                cases.stream().map(c -> c.outcome).toList());
        assertEquals(
                List.of("passes", "fails", "breaks", "skipped"),
                cases.stream().map(c -> c.method).toList());
        assertEquals("expected: <201> but was: <500>", cases.get(1).message);
        assertEquals("java.lang.IllegalStateException", cases.get(2).failureType);
    }

    @Test
    void shouldKeepTheScenarioTagsOfParameterizedAndRepeatedTests() throws Exception {
        report("com.acme.OrderIT", """
                <testcase classname="com.acme.OrderIT" name="rejects(String)[1]"/>
                <testcase classname="com.acme.OrderIT" name="rejects(String)[2]"/>
                <testcase classname="com.acme.OrderIT" name="retries()[3]"/>
                """);
        tags("""
                {"className":"com.acme.OrderIT","method":"rejects","tags":["gta","scenario:order.create/s02"]}
                {"className":"com.acme.OrderIT","method":"retries","tags":["gta","scenario:order.create/s03"]}
                """);

        List<TestOutcome> cases = SurefireReportParser.parse(List.of(reports), gtaResults);

        assertEquals(
                List.of("order.create/s02", "order.create/s02", "order.create/s03"),
                cases.stream().map(TestOutcome::scenarioId).toList(),
                "every invocation keeps the scenario of its method");
    }

    @Test
    void shouldTakeTheKnownDefectOutcomeFromGtaJunit() throws Exception {
        report("com.acme.OrderIT", "<testcase classname=\"com.acme.OrderIT\" name=\"pinned()\"/>\n");
        tags(
                "{\"className\":\"com.acme.OrderIT\",\"method\":\"pinned\",\"tags\":[\"gta\"],\"outcome\":\"known-defect\",\"displayName\":\"pinned\"}\n");

        TestOutcome c = SurefireReportParser.parse(List.of(reports), gtaResults).get(0);

        assertEquals("known-defect", c.outcome);
        assertEquals("pinned", c.displayName);
    }

    @Test
    void shouldFailClosedOnAnUnreadableReport() throws Exception {
        Files.writeString(reports.resolve("TEST-com.acme.OrderIT.xml"), "<testsuite><testcase name=");

        GtaException e =
                assertThrows(GtaException.class, () -> SurefireReportParser.parse(List.of(reports), gtaResults));

        assertEquals(
                ErrorCode.ENV_TEST_REPORTS_UNUSABLE,
                e.code(),
                "dropping its tests silently would read as 'no test ran'");
    }

    @Test
    void shouldTolerateATruncatedReportAfterATimeout() throws Exception {
        Files.writeString(reports.resolve("TEST-com.acme.HangingIT.xml"), "<testsuite><testcase name=");
        report("com.acme.OrderIT", "<testcase classname=\"com.acme.OrderIT\" name=\"passes()\"/>\n");

        List<TestOutcome> cases = SurefireReportParser.parse(List.of(reports), gtaResults, true);

        assertEquals(1, cases.size());
    }

    @Test
    void shouldSkipOnlyTheBrokenLineOfTheListenerOutput() throws Exception {
        report("com.acme.OrderIT", """
                <testcase classname="com.acme.OrderIT" name="first()"/>
                <testcase classname="com.acme.OrderIT" name="second()"/>
                """);
        tags(
                "{\"className\":\"com.acme.OrderIT\",\"method\":\"first\",\"tags\":[\"scenario:order.create/s01\"]}\n"
                        + "{\"className\":\"com.acme.OrderIT\",\"meth\n"
                        + "{\"className\":\"com.acme.OrderIT\",\"method\":\"second\",\"tags\":[\"scenario:order.create/s02\"]}\n");

        List<TestOutcome> cases = SurefireReportParser.parse(List.of(reports), gtaResults);

        assertEquals(
                List.of("order.create/s01", "order.create/s02"),
                cases.stream().map(TestOutcome::scenarioId).toList());
    }

    @Test
    void shouldIgnoreMissingDirectories() {
        assertEquals(List.of(), SurefireReportParser.parse(List.of(dir.resolve("none")), dir.resolve("none-either")));
    }

    @Test
    void shouldReduceAReportedNameToTheMethodName() {
        assertEquals("shouldCreate", SurefireReportParser.methodName("shouldCreate()"));
        assertEquals("rejects", SurefireReportParser.methodName("rejects(String, int)[12]"));
        assertEquals("plain", SurefireReportParser.methodName("plain"));
    }

    @Test
    void shouldRefuseAReportWithADoctype() throws Exception {
        Files.writeString(
                reports.resolve("TEST-com.acme.OrderIT.xml"),
                "<?xml version=\"1.0\"?><!DOCTYPE t [<!ENTITY x SYSTEM \"file:///etc/hostname\">]>"
                        + "<testsuite><testcase classname=\"com.acme.OrderIT\" name=\"&x;\"/></testsuite>");

        GtaException e =
                assertThrows(GtaException.class, () -> SurefireReportParser.parse(List.of(reports), gtaResults));

        assertEquals(ErrorCode.ENV_TEST_REPORTS_UNUSABLE, e.code(), "external entities are never resolved");
    }

    @Test
    void shouldReadOnlyTestReportFiles() throws Exception {
        report("com.acme.OrderIT", "<testcase classname=\"com.acme.OrderIT\" name=\"passes()\"/>\n");
        Files.writeString(reports.resolve("failsafe-summary.xml"), "<failsafe-summary result=\"254\"");

        assertEquals(1, SurefireReportParser.parse(List.of(reports), gtaResults).size());
    }

    @Test
    void shouldDropTheTypePrefixGradlePutsInFrontOfTheMessage() {
        assertEquals(
                "boom",
                SurefireReportParser.withoutTypePrefix(
                        "java.lang.IllegalStateException: boom", "java.lang.IllegalStateException"));
        assertEquals("boom", SurefireReportParser.withoutTypePrefix("boom", "java.lang.IllegalStateException"));
        assertEquals(
                "",
                SurefireReportParser.withoutTypePrefix(
                        "java.lang.IllegalStateException", "java.lang.IllegalStateException"),
                "Gradle writes an exception without a message as its type: the fingerprint uses an empty message");
        assertNull(SurefireReportParser.withoutTypePrefix(null, "x.Y"));
    }

    @Test
    void shouldNeverTurnAFailureInTheReportIntoAKnownDefect() throws Exception {
        // parameterized invocations share one gta-junit key: the last one (a known defect) must not hide the failure
        report(
                "com.acme.OrderIT",
                "<testcase classname=\"com.acme.OrderIT\" name=\"pinned(int)[1]\"><failure type=\"java.lang.AssertionError\" message=\"no longer reproduces\"/></testcase>\n"
                        + "<testcase classname=\"com.acme.OrderIT\" name=\"pinned(int)[2]\"><skipped/></testcase>\n");
        tags(
                "{\"className\":\"com.acme.OrderIT\",\"method\":\"pinned\",\"tags\":[\"gta\"],\"outcome\":\"known-defect\"}\n");

        List<TestOutcome> cases = SurefireReportParser.parse(List.of(reports), gtaResults);

        assertEquals("failed", cases.get(0).outcome);
        assertEquals("known-defect", cases.get(1).outcome);
    }

    @Test
    void shouldNotGuessWhichOfTwoSameNamedCucumberScenariosAReportCaseIs() throws Exception {
        report("com.acme.RunCucumberTest", """
                <testcase classname="Orders" name="Happy path"/>
                <testcase classname="Refunds" name="Happy path"><failure type="java.lang.AssertionError" message="no">stack</failure></testcase>
                """);
        tags("""
                {"className":"com.acme.RunCucumberTest","method":"Happy path","tags":["scenario:order.create/s01"],"outcome":"passed","feature":"features/orders.feature"}
                {"className":"com.acme.RunCucumberTest","method":"Happy path","tags":["scenario:refund.create/s01"],"outcome":"failed","feature":"features/refunds.feature"}
                """);

        List<TestOutcome> cases = SurefireReportParser.parse(List.of(reports), gtaResults);

        assertTrue(
                cases.stream().allMatch(c -> c.scenarioId() == null),
                "neither result is given to the other scenario: "
                        + cases.stream().map(TestOutcome::scenarioId).toList());
    }

    @Test
    void shouldMapACucumberScenarioByItsNameAndTakeThePinVerdictOfGtaJunit() throws Exception {
        // Surefire names a Cucumber scenario after the scenario, under the suite's simple name
        report("com.acme.RunCucumberTest", """
                <testcase classname="RunCucumberTest" name="[borrow.book/s01] a borrow of no shares is refused"/>
                <testcase classname="RunCucumberTest" name="[borrow.book/s02] pending (inventory down)"><failure type="org.opentest4j.AssertionFailedError" message="expected: 503 but was: 201">stack</failure></testcase>
                <testcase classname="RunCucumberTest" name="[borrow.book/s03] fixed"/>
                """);
        tags("""
                {"className":"com.acme.RunCucumberTest","method":"[borrow.book/s01] a borrow of no shares is refused","tags":["gta","scenario:borrow.book/s01"],"outcome":"passed","feature":"features/borrow/book.feature"}
                {"className":"com.acme.RunCucumberTest","method":"[borrow.book/s02] pending (inventory down)","tags":["scenario:borrow.book/s02","known-defect"],"outcome":"known-defect","message":"gta-known-defect: still failing","feature":"features/borrow/book.feature","pinned":true}
                {"className":"com.acme.RunCucumberTest","method":"[borrow.book/s03] fixed","tags":["scenario:borrow.book/s03"],"outcome":"failed","message":"Known defect no longer reproduces","feature":"features/borrow/book.feature","pinned":true}
                """);

        List<TestOutcome> cases = SurefireReportParser.parse(List.of(reports), gtaResults);

        assertEquals(
                List.of("borrow.book/s01", "borrow.book/s02", "borrow.book/s03"),
                cases.stream().map(TestOutcome::scenarioId).toList());
        assertEquals("com.acme.RunCucumberTest", cases.get(0).className, "the suite that runs the feature");
        assertEquals(
                List.of("passed", "known-defect", "failed"),
                cases.stream().map(c -> c.outcome).toList(),
                "a pinned scenario still failing as recorded is a known defect; a pinned one that passes fails");
        assertEquals("Known defect no longer reproduces", cases.get(2).message);
    }
}
