package com.gta.core.oracle;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.gta.core.profile.JavaIndex;
import com.gta.core.util.Hashes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Extracts the oracle of a scenario's test methods (methods tagged {@code @Tag("scenario:<id>")}):
 * assertion statements, the expected-value tokens inside them, and a hash of everything else that is "watched"
 * (inputs, setup, referenced fixture files). Used for the THEN-literal check and the oracle lock (plan §5.3).
 */
public final class AssertionScanner {
    private static final Set<String> ASSERTION_CALLS = Set.of(
            "isEqualTo",
            "isNotEqualTo",
            "containsExactly",
            "containsExactlyInAnyOrder",
            "containsEntry",
            "contains",
            "hasSize",
            "hasStatus",
            "andExpect",
            "statusCode",
            "expectStatus",
            "expectBody",
            "isEqualByComparingTo",
            "hasFieldOrPropertyWithValue",
            "isTrue",
            "isFalse",
            "isNull",
            "isNotNull",
            "isEmpty",
            "isNotEmpty",
            "isPresent",
            "hasMessage",
            "hasMessageContaining",
            "isInstanceOf",
            "matches",
            "startsWith",
            "endsWith",
            "satisfies",
            "hasValue",
            "isZero",
            "isPositive");
    private static final Map<String, String> STATUS_NAMES = Map.ofEntries(
            Map.entry("OK", "200"),
            Map.entry("CREATED", "201"),
            Map.entry("ACCEPTED", "202"),
            Map.entry("NO_CONTENT", "204"),
            Map.entry("BAD_REQUEST", "400"),
            Map.entry("UNAUTHORIZED", "401"),
            Map.entry("FORBIDDEN", "403"),
            Map.entry("NOT_FOUND", "404"),
            Map.entry("CONFLICT", "409"),
            Map.entry("UNPROCESSABLE_ENTITY", "422"),
            Map.entry("UNPROCESSABLE_CONTENT", "422"),
            Map.entry("TOO_MANY_REQUESTS", "429"),
            Map.entry("INTERNAL_SERVER_ERROR", "500"),
            Map.entry("BAD_GATEWAY", "502"),
            Map.entry("SERVICE_UNAVAILABLE", "503"),
            Map.entry("GATEWAY_TIMEOUT", "504"));
    private static final Map<String, String> STATUS_MATCHERS = Map.ofEntries(
            Map.entry("isOk", "200"),
            Map.entry("isCreated", "201"),
            Map.entry("isAccepted", "202"),
            Map.entry("isNoContent", "204"),
            Map.entry("isBadRequest", "400"),
            Map.entry("isUnauthorized", "401"),
            Map.entry("isForbidden", "403"),
            Map.entry("isNotFound", "404"),
            Map.entry("isConflict", "409"),
            Map.entry("isUnprocessableEntity", "422"),
            Map.entry("isInternalServerError", "500"),
            Map.entry("isServiceUnavailable", "503"),
            Map.entry("isGatewayTimeout", "504"));

    private static final Set<String> STIMULUS_CALLS = Set.of(
            "perform",
            "post",
            "get",
            "put",
            "patch",
            "delete",
            "head",
            "options",
            "request",
            "multipart",
            "content",
            "contentType",
            "accept",
            "header",
            "headers",
            "param",
            "params",
            "queryParam",
            "cookie",
            "uri",
            "body",
            "bodyValue",
            "exchange",
            "postForEntity",
            "postForObject",
            "getForEntity",
            "getForObject",
            "send",
            "convertAndSend",
            "publish",
            "launchJob",
            "launchStep",
            "given",
            "when");

    /** Oracle view of one scenario's tests. */
    public record ScenarioOracle(
            int methods, List<String> assertionStatements, Set<String> tokens, String watchedHash) {
        public boolean found() {
            return methods > 0;
        }
    }

    private AssertionScanner() {}

    public static ScenarioOracle scan(Path testFile, String scenarioId, Path testResources) {
        if (!Files.exists(testFile)) {
            return new ScenarioOracle(0, List.of(), Set.of(), "");
        }
        ParseResult<CompilationUnit> parsed;
        try {
            parsed = JavaIndex.parser().parse(testFile);
        } catch (IOException e) {
            return new ScenarioOracle(0, List.of(), Set.of(), "");
        }
        if (parsed.getResult().isEmpty()) {
            return new ScenarioOracle(0, List.of(), Set.of(), "");
        }
        CompilationUnit cu = parsed.getResult().get();
        Map<String, String> constants = constants(cu);
        List<String> statements = new ArrayList<>();
        Set<String> tokens = new LinkedHashSet<>();
        StringBuilder watched = new StringBuilder();
        int methods = 0;
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            if (!isTest(m) || (scenarioId != null && !tags(m).contains("scenario:" + scenarioId))) {
                continue;
            }
            methods++;
            for (ExpressionStmt stmt : m.findAll(ExpressionStmt.class)) {
                if (isAssertion(stmt)) {
                    // a MockMvc/RestTestClient chain mixes the stimulus and the expectations in one statement:
                    // only the assertion fragments are locked; the rest of the statement is an input (watched)
                    String rest = normalize(stmt);
                    for (MethodCallExpr fragment : assertionFragments(stmt)) {
                        String f = fragment.getNameAsString() + "("
                                + fragment.getArguments().stream()
                                        .map(AssertionScanner::normalize)
                                        .reduce((a, b) -> a + "," + b)
                                        .orElse("")
                                + ")";
                        statements.add(f);
                        rest = rest.replace(f, "");
                    }
                    watched.append(rest);
                    collectTokens(stmt, constants, tokens);
                } else {
                    watched.append(normalize(stmt));
                }
            }
            // every string of the method, its annotations (@Sql) included, may name a fixture
            for (StringLiteralExpr s : m.findAll(StringLiteralExpr.class)) {
                hashResource(s.getValue(), testResources, watched);
            }
        }
        // setup methods and fields belong to the inputs of every scenario in the class
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            if (m.getAnnotationByName("BeforeEach").isPresent()
                    || m.getAnnotationByName("BeforeAll").isPresent()) {
                watched.append(normalize(m));
            }
        }
        cu.findAll(FieldDeclaration.class).forEach(f -> watched.append(normalize(f)));
        return new ScenarioOracle(methods, statements, tokens, Hashes.shortSha(watched.toString()));
    }

    static boolean isTest(MethodDeclaration m) {
        return m.getAnnotations().stream().anyMatch(a -> {
            String n = a.getNameAsString();
            return n.equals("Test")
                    || n.equals("ParameterizedTest")
                    || n.equals("RepeatedTest")
                    || n.equals("TestFactory");
        });
    }

    static Set<String> tags(MethodDeclaration m) {
        Set<String> tags = new LinkedHashSet<>();
        for (AnnotationExpr a : m.getAnnotations()) {
            if (a.getNameAsString().equals("Tag") || a.getNameAsString().equals("Tags")) {
                a.findAll(StringLiteralExpr.class).forEach(s -> tags.add(s.getValue()));
            }
        }
        return tags;
    }

    static boolean isAssertionCall(MethodCallExpr c) {
        String n = c.getNameAsString();
        return n.startsWith("assert")
                || n.startsWith("verify")
                || ASSERTION_CALLS.contains(n)
                || STATUS_MATCHERS.containsKey(n);
    }

    static boolean isAssertion(Statement stmt) {
        return stmt.findAll(MethodCallExpr.class).stream().anyMatch(AssertionScanner::isAssertionCall);
    }

    /** Outermost assertion calls of a statement (an assertion nested in another one's arguments belongs to it). */
    static List<MethodCallExpr> assertionFragments(Statement stmt) {
        List<MethodCallExpr> out = new ArrayList<>();
        for (MethodCallExpr c : stmt.findAll(MethodCallExpr.class)) {
            if (isAssertionCall(c) && !inArgumentsOf(c, AssertionScanner::isAssertionCall)) {
                out.add(c);
            }
        }
        return out;
    }

    /** True when the node sits inside the arguments (not the scope) of an enclosing call matching the predicate. */
    static boolean inArgumentsOf(Node node, Predicate<MethodCallExpr> predicate) {
        Node child = node;
        Optional<Node> parent = node.getParentNode();
        while (parent.isPresent() && !(parent.get() instanceof Statement)) {
            if (parent.get() instanceof MethodCallExpr call && predicate.test(call)) {
                Node c = child;
                if (call.getArguments().stream().anyMatch(a -> a == c)) {
                    return true;
                }
            }
            child = parent.get();
            parent = child.getParentNode();
        }
        return false;
    }

    /** Request-building calls: their arguments are inputs (stimulus), not expected values. */
    static boolean inStimulus(Node node) {
        Node child = node;
        Optional<Node> parent = node.getParentNode();
        while (parent.isPresent() && !(parent.get() instanceof Statement)) {
            if (parent.get() instanceof MethodCallExpr call) {
                Node c = child;
                boolean inArgs = call.getArguments().stream().anyMatch(a -> a == c);
                if (inArgs && isAssertionCall(call)) {
                    return false; // expected side wins, e.g. andExpect(content().string("x"))
                }
                if (inArgs && STIMULUS_CALLS.contains(call.getNameAsString())) {
                    return true;
                }
            }
            child = parent.get();
            parent = child.getParentNode();
        }
        return false;
    }

    static void collectTokens(Statement stmt, Map<String, String> constants, Set<String> tokens) {
        for (LiteralExpr lit : stmt.findAll(LiteralExpr.class)) {
            if (inStimulus(lit)) {
                continue;
            }
            if (lit instanceof StringLiteralExpr s) {
                tokens.add(s.getValue());
            } else if (lit instanceof TextBlockLiteralExpr t) {
                tokens.add(t.getValue());
            } else if (!lit.isBooleanLiteralExpr() && !lit.isNullLiteralExpr()) {
                tokens.add(lit.toString().replaceAll("[lLdDfF]$", "").replace("_", ""));
            }
        }
        for (NameExpr n : stmt.findAll(NameExpr.class)) {
            if (!inStimulus(n)) {
                addSymbol(n.getNameAsString(), constants, tokens);
            }
        }
        for (FieldAccessExpr f : stmt.findAll(FieldAccessExpr.class)) {
            if (!inStimulus(f)) {
                addSymbol(f.getNameAsString(), constants, tokens);
            }
        }
        for (MethodCallExpr c : stmt.findAll(MethodCallExpr.class)) {
            String code = inStimulus(c) ? null : STATUS_MATCHERS.get(c.getNameAsString());
            if (code != null) {
                tokens.add(code);
            }
        }
    }

    private static void addSymbol(String name, Map<String, String> constants, Set<String> tokens) {
        if (constants.containsKey(name)) {
            tokens.add(constants.get(name));
        }
        if (name.matches("[A-Z][A-Z0-9_]+")) {
            tokens.add(name);
            String code = STATUS_NAMES.get(name);
            if (code != null) {
                tokens.add(code);
            }
        }
    }

    /** static final fields with a literal initialiser, resolved by simple name. */
    static Map<String, String> constants(CompilationUnit cu) {
        Map<String, String> out = new HashMap<>();
        for (FieldDeclaration f : cu.findAll(FieldDeclaration.class)) {
            if (!f.hasModifier(Modifier.Keyword.STATIC) || !f.hasModifier(Modifier.Keyword.FINAL)) {
                continue;
            }
            for (VariableDeclarator v : f.getVariables()) {
                v.getInitializer()
                        .filter(Expression::isLiteralExpr)
                        .ifPresent(init -> out.put(
                                v.getNameAsString(),
                                init.isStringLiteralExpr()
                                        ? init.asStringLiteralExpr().getValue()
                                        : init.toString()));
            }
        }
        return out;
    }

    private static void hashResource(String value, Path testResources, StringBuilder watched) {
        if (testResources == null || !value.matches("[\\w./-]+\\.(json|sql|ya?ml|xml|txt|csv|properties)")) {
            return;
        }
        String rel = value.startsWith("classpath:") ? value.substring(10) : value;
        Path file = testResources.resolve(rel.startsWith("/") ? rel.substring(1) : rel);
        if (Files.isRegularFile(file)) {
            watched.append(rel).append('=').append(Hashes.sha256(file));
        }
    }

    static String normalize(Object node) {
        return node.toString().replaceAll("\\s+", "");
    }

    /** THEN literal check: every expected value of the spec must appear in an assertion (plan §5.3 item 2). */
    public static List<String> missingThenLiterals(List<String> thenLiterals, Set<String> tokens) {
        List<String> missing = new ArrayList<>();
        for (String lit : thenLiterals) {
            boolean ok = tokens.contains(lit) || tokens.stream().anyMatch(t -> lit.length() >= 3 && t.contains(lit));
            if (!ok) {
                missing.add(lit);
            }
        }
        return missing;
    }
}
