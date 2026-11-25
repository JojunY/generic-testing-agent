package com.gta.core.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssertionScannerTest {
    static final String SCENARIO = "order.create/s01";
    static final String TEST = """
            package com.acme;
            class OrderIT {
                static final String CREATED = "CREATED";
                @Test
                @Tag("scenario:order.create/s01")
                void shouldCreate() throws Exception {
                    mvc.perform(post("/api/orders").content("{\\"subtotal\\":50.00}"))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.total").value(50.00))
                            .andExpect(jsonPath("$.status").value(CREATED));
                }
                @Test
                @Tag("scenario:order.create/s02")
                void other() {
                    assertThat(total).isEqualTo("99.99");
                }
            }
            """;

    @TempDir
    Path dir;

    private AssertionScanner.ScenarioOracle scan(String source) throws Exception {
        Path file = dir.resolve("OrderIT.java");
        Files.writeString(file, source);
        return AssertionScanner.scan(file, SCENARIO, null);
    }

    @Test
    void shouldCollectExpectedValuesFromAssertionsOfTheScenarioOnly() throws Exception {
        AssertionScanner.ScenarioOracle o = scan(TEST);

        assertEquals(1, o.methods());
        assertTrue(
                o.tokens().containsAll(List.of("201", "50.00", "CREATED")),
                o.tokens().toString());
        assertFalse(o.tokens().contains("99.99"), "other scenario leaked in");
    }

    @Test
    void shouldNotCountRequestInputsAsExpectedValues() throws Exception {
        AssertionScanner.ScenarioOracle o = scan(TEST.replace("value(50.00)", "value(51.00)"));

        assertEquals(List.of("50.00"), AssertionScanner.missingThenLiterals(List.of("201", "50.00"), o.tokens()));
    }

    @Test
    void shouldDetectAModifiedLockedValue() throws Exception {
        Lock lock = OracleLock.create(scan(TEST), List.of("201", "50.00", "CREATED"), "r1");

        OracleLock.Check check = OracleLock.check(lock, scan(TEST.replace("value(50.00)", "value(51.00)")));

        assertEquals(OracleLock.Verdict.MODIFIED, check.verdict());
        assertEquals(List.of("50.00"), check.missingTokens());
    }

    @Test
    void shouldAllowChangedInputsAndAddedAssertions() throws Exception {
        Lock lock = OracleLock.create(scan(TEST), List.of("201", "50.00", "CREATED"), "r1");
        String changed = TEST.replace("\\\"subtotal\\\":50.00", "\\\"subtotal\\\":50.000")
                .replace(
                        ".andExpect(jsonPath(\"$.status\").value(CREATED));",
                        ".andExpect(jsonPath(\"$.status\").value(CREATED))\n.andExpect(jsonPath(\"$.id\").exists());");

        AssertionScanner.ScenarioOracle now = scan(changed);

        assertEquals(OracleLock.Verdict.OK, OracleLock.check(lock, now).verdict());
        assertFalse(lock.watched.equals(now.watchedHash()), "input change must change the watched hash");
    }

    private AssertionScanner.ScenarioOracle scanSource(String body) throws Exception {
        Path f = dir.resolve("ScanIT.java");
        Files.writeString(
                f, "import static org.assertj.core.api.Assertions.assertThat;\n" + "class ScanIT {\n" + body + "\n}\n");
        return AssertionScanner.scan(f, "order.create/s01", dir.resolve("resources"));
    }

    @Test
    void shouldWatchHelpersAndTheFixturesOfTheClassAndItsConstants() throws Exception {
        Path fixtures = dir.resolve("resources/fixtures");
        Files.createDirectories(fixtures);
        Files.writeString(fixtures.resolve("orders.sql"), "insert 1;");
        Files.writeString(fixtures.resolve("order.json"), "{}");
        String body = """
                    private static final String ORDER = "fixtures/order.json";
                    @Test @Tag("scenario:order.create/s01")
                    void shouldCreate() { assertThat(create(5)).isEqualTo(201); }
                    private int create(int qty) { return client.post(qty); }
                """;
        String before = scanSource(body).watchedHash();

        assertNotEquals(
                before, scanSource(body.replace("post(qty)", "post(qty + 1)")).watchedHash(), "a helper");
        Files.writeString(fixtures.resolve("order.json"), "{\"qty\": 9}");
        String afterConstant = scanSource(body).watchedHash();
        assertNotEquals(before, afterConstant, "a fixture named by a constant");
        Path test = dir.resolve("ScanIT.java");
        Files.writeString(test, "@Sql(\"fixtures/orders.sql\")\nclass ScanIT {\n" + body + "\n}\n");
        String classSql = AssertionScanner.scan(test, "order.create/s01", dir.resolve("resources"))
                .watchedHash();
        Files.writeString(fixtures.resolve("orders.sql"), "insert 2;");
        assertNotEquals(
                classSql,
                AssertionScanner.scan(test, "order.create/s01", dir.resolve("resources"))
                        .watchedHash(),
                "a class-level @Sql");
    }

    @Test
    void shouldLockTheSubjectOfAnAssertionWithIt() throws Exception {
        String body = """
                    @Test @Tag("scenario:order.create/s01")
                    void shouldRefuse() { assertThat(response.getStatusCode().value()).isEqualTo(503); }
                """;
        Lock lock = OracleLock.create(scanSource(body), List.of("503"), "r1");

        assertEquals(List.of("assertThat(response.getStatusCode().value()).isEqualTo(503)"), lock.statements);
        assertEquals(
                OracleLock.Verdict.STRUCTURAL_CHANGE,
                OracleLock.check(lock, scanSource(body.replace("response.getStatusCode()", "cached.getStatusCode()")))
                        .verdict(),
                "asserting the value on another object is a change to the locked assertion");
    }

    @Test
    void shouldLockWhatAVerificationExpects() throws Exception {
        String body = """
                    @Test @Tag("scenario:order.create/s01")
                    void shouldPublish() {
                        verify(producer).send("orders", payload);
                        given().when().post("/orders").then().statusCode(201).body("status", equalTo("CREATED"));
                    }
                """;
        AssertionScanner.ScenarioOracle first = scanSource(body);

        assertEquals(
                List.of(
                        "verify(producer).send(\"orders\",payload)",
                        "statusCode(201).body(\"status\",equalTo(\"CREATED\"))"),
                OracleLock.lockedStatements(first.assertionStatements(), List.of("orders", "CREATED")));
        String changed = body.replace("send(\"orders\"", "send(\"audit\"");
        assertEquals(
                List.of("verify(producer).send(\"audit\",payload)"),
                scanSource(changed).assertionStatements().stream()
                        .filter(t -> t.startsWith("verify"))
                        .toList());
    }

    @Test
    void shouldLockAnAssertionOfAClassConstantByItsValue() throws Exception {
        String body = """
                    private static final String MSG = "Order not found";
                    @Test @Tag("scenario:order.create/s01")
                    void shouldRefuse() { assertThat(error.message()).isEqualTo(MSG); }
                """;
        AssertionScanner.ScenarioOracle first = scanSource(body);
        Lock lock = OracleLock.create(first, List.of("Order not found"), "r1");

        assertEquals(
                List.of("assertThat(error.message()).isEqualTo(\"Ordernotfound\")"),
                lock.statements,
                "the constant is locked by its value");
        assertEquals(
                OracleLock.Verdict.STRUCTURAL_CHANGE,
                OracleLock.check(lock, scanSource(body.replace("isEqualTo(MSG)", "contains(MSG)")))
                        .verdict(),
                "a weakened assertion of the constant is caught");
    }

    @Test
    void shouldResolveConstantsStatusNamesAndMatchers() throws Exception {
        AssertionScanner.ScenarioOracle o = scanSource("""
                    static final String TOTAL = "50.00";
                    @Test @Tag("scenario:order.create/s01")
                    void shouldCreate() throws Exception {
                        assertThat(response.total()).isEqualTo(TOTAL);
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                        mvc.perform(post("/api/orders").content("{\\"subtotal\\":50}")).andExpect(status().isCreated());
                        assertThat(response.body()).isEqualTo(\"\"\"
                            {"id": 1}
                            \"\"\");
                        assertThat(response.count()).isEqualTo(3L);
                    }
                """);

        assertTrue(
                o.tokens().containsAll(java.util.List.of("50.00", "201", "CREATED", "3")),
                o.tokens().toString());
        assertTrue(o.tokens().stream().anyMatch(t -> t.contains("\"id\": 1")), "text blocks are expected values");
        assertEquals(1, o.methods());
    }

    @Test
    void shouldWatchFixturesSqlAndSetupAsInputs() throws Exception {
        Files.createDirectories(dir.resolve("resources"));
        Files.writeString(dir.resolve("resources/s01.request.json"), "{\"subtotal\": 50}");
        String body = """
                    @BeforeEach void seed() { repo.save(order()); }
                    @Test @Tag("scenario:order.create/s01") @Sql("classpath:/s01.data.sql")
                    void shouldCreate() { post(fixture("s01.request.json")); assertThat(status).isEqualTo(201); }
                """;
        String before = scanSource(body).watchedHash();

        Files.writeString(dir.resolve("resources/s01.request.json"), "{\"subtotal\": 51}");

        assertFalse(before.equals(scanSource(body).watchedHash()), "a changed fixture changes the watched inputs");
        assertFalse(
                before.equals(scanSource(body.replace("order()", "order(2)")).watchedHash()), "so does the setup");
    }

    @Test
    void shouldWatchAClasspathSqlScript() throws Exception {
        Files.createDirectories(dir.resolve("resources"));
        Files.writeString(dir.resolve("resources/s01.data.sql"), "insert into orders values (1);");
        String body = """
                    @Test @Tag("scenario:order.create/s01") @Sql("classpath:/s01.data.sql")
                    void shouldCreate() { assertThat(status).isEqualTo(201); }
                """;
        String before = scanSource(body).watchedHash();

        Files.writeString(dir.resolve("resources/s01.data.sql"), "insert into orders values (2);");

        assertFalse(before.equals(scanSource(body).watchedHash()), "a changed classpath: script changes the inputs");
    }

    @Test
    void shouldFindNothingInAMissingOrBrokenFile() throws Exception {
        assertFalse(AssertionScanner.scan(dir.resolve("None.java"), "order.create/s01", null)
                .found());
        Path broken = dir.resolve("Broken.java");
        Files.writeString(broken, "class Broken { void x( { }");
        assertFalse(AssertionScanner.scan(broken, "order.create/s01", null).found());
    }

    @Test
    void shouldMatchLongThenValuesInsideALargerToken() {
        assertEquals(
                java.util.List.of(),
                AssertionScanner.missingThenLiterals(
                        java.util.List.of("PENDING"), java.util.Set.of("status PENDING_PAYMENT")));
        assertEquals(
                java.util.List.of("5"),
                AssertionScanner.missingThenLiterals(java.util.List.of("5"), java.util.Set.of("50")),
                "short values must match exactly");
    }

    @Test
    void shouldNotMatchANumberInsideAnotherNumber() {
        for (String other : List.of("-100", "1000", "100.5", "2100")) {
            assertEquals(
                    List.of("100"),
                    AssertionScanner.missingThenLiterals(List.of("100"), Set.of(other)),
                    other + " does not assert 100");
        }
        assertEquals(List.of(), AssertionScanner.missingThenLiterals(List.of("100"), Set.of("{\"amount\":100}")));
        assertEquals(List.of(), AssertionScanner.missingThenLiterals(List.of("100.00"), Set.of("total 100.00 EUR")));
    }

    @Test
    void shouldCountEveryKindOfTestMethodButNoHelper() throws Exception {
        AssertionScanner.ScenarioOracle o = scanSource("""
                    @ParameterizedTest @Tag("scenario:order.create/s01") void a(int x) { assertThat(x).isEqualTo(1); }
                    @RepeatedTest(2) @Tag("scenario:order.create/s01") void b() { assertThat(y).isEqualTo(2); }
                    @TestFactory @Tag("scenario:order.create/s01") Object c() { assertThat(z).isEqualTo(3); return null; }
                    @Test @Tags({@Tag("gta"), @Tag("scenario:order.create/s01")}) void d() { assertThat(w).isEqualTo(4); }
                    @Tag("scenario:order.create/s01") void helper() { assertThat(v).isEqualTo(5); }
                """);

        assertEquals(4, o.methods());
        assertTrue(
                o.tokens().containsAll(List.of("1", "2", "3", "4")), o.tokens().toString());
        assertFalse(o.tokens().contains("5"), "a helper without a test annotation is not a scenario");
    }

    @Test
    void shouldTakeExpectedValuesFromAMatcherEvenInsideTheRequestChain() throws Exception {
        AssertionScanner.ScenarioOracle o = scanSource("""
                    @Test @Tag("scenario:order.create/s01")
                    void shouldPay() throws Exception {
                        mvc.perform(post("/api/pay").content("REQUEST-BODY")).andExpect(content().string("PAID"));
                    }
                """);

        assertTrue(o.tokens().contains("PAID"), o.tokens().toString());
        assertFalse(o.tokens().contains("REQUEST-BODY"), "the request body is an input");
        assertFalse(o.tokens().contains("/api/pay"), "the request path is an input");
    }

    @Test
    void shouldLockAMatcherInsideAnExpectationAsPartOfThatExpectation() throws Exception {
        List<String> statements = scan(TEST).assertionStatements();

        assertEquals(3, statements.size(), statements.toString());
        assertTrue(statements.contains("andExpect(status().isCreated())"), statements.toString());
        assertTrue(statements.stream().noneMatch(st -> st.startsWith("isCreated")), "not locked on its own");
    }

    @Test
    void shouldWatchSqlScriptsFieldsAndSetupButNotOtherScenarios() throws Exception {
        Files.createDirectories(dir.resolve("resources"));
        Files.writeString(dir.resolve("resources/s01.data.sql"), "insert into orders values (1);");
        String body = """
                    private final int seedCount = 1;
                    @BeforeAll static void start() { db.start(); }
                    @Test @Tag("scenario:order.create/s01") @Sql("s01.data.sql")
                    void shouldCreate() { assertThat(status).isEqualTo(201); }
                    @Test @Tag("scenario:order.create/s02")
                    void other() { assertThat(status).isEqualTo(400); }
                """;
        String before = scanSource(body).watchedHash();

        assertEquals(before, scanSource(body.replace("400", "409")).watchedHash(), "another scenario is not an input");
        assertFalse(before.equals(scanSource(body.replace("= 1;", "= 2;")).watchedHash()), "a field is an input");
        assertFalse(
                before.equals(
                        scanSource(body.replace("db.start()", "db.reset()")).watchedHash()),
                "@BeforeAll too");

        Files.writeString(dir.resolve("resources/s01.data.sql"), "insert into orders values (2);");
        assertFalse(before.equals(scanSource(body).watchedHash()), "a changed @Sql script changes the inputs");
    }

    @Test
    void shouldTakeWhatATestVerifiesWasSentAsExpectedAndWhatItSendsAsInput() throws Exception {
        AssertionScanner.ScenarioOracle o = scanSource("""
                    @Test @Tag("scenario:order.create/s01")
                    void shouldPublishOrderCreated() {
                        kafkaTemplate.send("orders-in", "INPUT-EVENT");
                        verify(producer).send("orders-out", "EXPECTED-EVENT");
                    }
                """);

        assertTrue(
                o.tokens().contains("EXPECTED-EVENT"), "verified outgoing messages are expected values: " + o.tokens());
        assertFalse(o.tokens().contains("INPUT-EVENT"), "the message the test sends is an input");
    }

    @Test
    void shouldTakeBooleanExpectedValuesFromLiteralsAndBooleanAssertions() throws Exception {
        AssertionScanner.ScenarioOracle o = scanSource("""
                    @Test @Tag("scenario:order.create/s01")
                    void shouldArchive() {
                        assertThat(repository.isArchived(id)).isEqualTo(true);
                        assertThat(repository.isLocked(id)).isFalse();
                    }
                """);

        assertTrue(o.tokens().containsAll(List.of("true", "false")), o.tokens().toString());
        assertEquals(List.of(), AssertionScanner.missingThenLiterals(List.of("true", "false"), o.tokens()));
    }

    @Test
    void shouldTreatAGraphqlDocumentAndItsVariablesAsInputs() throws Exception {
        AssertionScanner.ScenarioOracle o = scanSource("""
                    @Test @Tag("scenario:order.create/s01")
                    void shouldList() {
                        List<String> names = graphql.document("{ productsUpTo(maxPrice: 45.0) { name } }")
                                .variable("limit", 10).execute().path("productsUpTo[*].name").entityList(String.class).get();
                        assertThat(names).containsExactly("Kettle", "Toaster");
                    }
                """);

        assertTrue(
                o.tokens().containsAll(List.of("Kettle", "Toaster")), o.tokens().toString());
        assertFalse(o.tokens().stream().anyMatch(t -> t.contains("productsUpTo(maxPrice")), "the query is an input");
    }

    @Test
    void shouldTakeBeamPassertValuesAsExpectedAndTheCreatedElementsAsInputs() throws Exception {
        AssertionScanner.ScenarioOracle o = scanSource("""
                    @Test @Tag("scenario:order.create/s01")
                    void shouldReport(TestPipeline p) {
                        PCollection<String> report = p.apply(Create.of(KV.of("INPUT-NAME", 2))).apply(new LowStock());
                        PAssert.that(report).containsInAnyOrder("EXPECTED-NAME");
                        p.run().waitUntilFinish();
                    }
                """);

        assertTrue(o.tokens().contains("EXPECTED-NAME"), o.tokens().toString());
        assertFalse(o.tokens().contains("INPUT-NAME"), "the elements the pipeline starts from are inputs");
    }

    @Test
    void shouldReadANegativeNumberAsOneExpectedValue() throws Exception {
        AssertionScanner.ScenarioOracle o = scan("""
                class OrderIT {
                    @Test @Tag("scenario:order.create/s01")
                    void shouldCreate() { assertThat(balance).isEqualTo(-1); assertThat(delta).isEqualTo(-2.5d); }
                }
                """);

        assertEquals(List.of(), AssertionScanner.missingThenLiterals(List.of("-1", "-2.5"), o.tokens()));
        assertEquals(List.of("1"), AssertionScanner.missingThenLiterals(List.of("1"), o.tokens()));
    }

    @Test
    void shouldLockAnExpectationWithTheSubjectItAsserts() throws Exception {
        String body = """
                    @Test @Tag("scenario:order.create/s01")
                    void shouldRefuse() { assertThat(response.getStatusCode().value()).isEqualTo(404); }
                """;
        Lock lock = OracleLock.create(scanSource(body), List.of("404"), "r1");

        OracleLock.Check tautology = OracleLock.check(
                lock, scanSource(body.replace("assertThat(response.getStatusCode().value())", "assertThat(404)")));

        assertEquals(
                OracleLock.Verdict.STRUCTURAL_CHANGE,
                tautology.verdict(),
                "assertThat(404).isEqualTo(404) still holds the value but no longer checks the response");
    }

    @Test
    void shouldLockABddThenWithItsSubject() throws Exception {
        String body = """
                    @Test @Tag("scenario:order.create/s01")
                    void shouldRefuse() { then(order.status()).isEqualTo("PENDING"); }
                """;
        Lock lock = OracleLock.create(scanSource(body), List.of("PENDING"), "r1");

        OracleLock.Check tautology =
                OracleLock.check(lock, scanSource(body.replace("then(order.status())", "then(\"PENDING\")")));

        assertEquals(OracleLock.Verdict.STRUCTURAL_CHANGE, tautology.verdict(), "then(x) is assertThat(x)");
    }

    @Test
    void shouldLockAStepVerifierScriptWhole() throws Exception {
        String body = """
                    @Test @Tag("scenario:order.create/s01")
                    void shouldStream() { StepVerifier.create(service.statuses()).expectNext("PENDING").verifyComplete(); }
                """;
        Lock lock = OracleLock.create(scanSource(body), List.of("PENDING"), "r1");

        assertEquals(1, lock.statements.size(), "expectNext holds the expected value: " + lock.statements);
        assertEquals(
                OracleLock.Verdict.STRUCTURAL_CHANGE,
                OracleLock.check(lock, scanSource(body.replace("service.statuses()", "Flux.just(\"PENDING\")")))
                        .verdict(),
                "a script over a constant no longer checks the service");
    }

    @Test
    void shouldReadRestAssuredExpectationsAfterThenAsExpectedValues() throws Exception {
        AssertionScanner.ScenarioOracle o = scanSource("""
                    @Test @Tag("scenario:order.create/s01")
                    void shouldCreate() {
                        given().body("{\\"subtotal\\":50}").when().post("/orders")
                                .then().body("status", equalTo("PENDING")).statusCode(201);
                    }
                """);

        assertEquals(List.of(), AssertionScanner.missingThenLiterals(List.of("PENDING", "201"), o.tokens()));
        assertFalse(o.tokens().contains("{\"subtotal\":50}"), "the request body before then() is an input");
        Lock lock = OracleLock.create(o, List.of("PENDING"), "r1");
        assertEquals(1, lock.statements.size(), lock.statements.toString());
        assertTrue(lock.statements.get(0).startsWith("body("), "the body expectation is locked");
    }

    @Test
    void shouldMatchANumberOnlyAsAWholeNumber() {
        assertEquals(
                List.of("100", "200"),
                AssertionScanner.missingThenLiterals(List.of("100", "200"), java.util.Set.of("-100", "2000")));
        assertEquals(List.of(), AssertionScanner.missingThenLiterals(List.of("100"), java.util.Set.of("100.00")));
        assertEquals(List.of(), AssertionScanner.missingThenLiterals(List.of("100"), java.util.Set.of("total: 100")));
    }

    @Test
    void shouldWatchAClasspathFixture() throws Exception {
        Files.createDirectories(dir.resolve("resources/fixtures"));
        Files.writeString(dir.resolve("resources/fixtures/orders.sql"), "insert into orders values (1);");
        String body = """
                    @Test @Tag("scenario:order.create/s01") @Sql("classpath:fixtures/orders.sql")
                    void shouldCreate() { assertThat(status).isEqualTo(201); }
                """;
        String before = scanSource(body).watchedHash();

        Files.writeString(dir.resolve("resources/fixtures/orders.sql"), "insert into orders values (2);");

        assertFalse(before.equals(scanSource(body).watchedHash()), "the usual Spring form of @Sql is an input too");
    }
}
