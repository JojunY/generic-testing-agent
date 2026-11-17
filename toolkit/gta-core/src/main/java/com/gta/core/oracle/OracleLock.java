package com.gta.core.oracle;

import com.gta.core.util.Hashes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Monotonic oracle lock (plan §5.3 item 3): once the THEN-derived expected values are locked, the assertions carrying
 * them may not change or disappear; new assertions may be added. Inputs are watched, not locked.
 */
public final class OracleLock {
    public enum Verdict {
        OK,
        STRUCTURAL_CHANGE,
        MODIFIED
    }

    public record Check(Verdict verdict, List<String> missingTokens) {}

    private OracleLock() {}

    /** Creates the lock from the first run that reached the system under test. */
    public static Lock create(AssertionScanner.ScenarioOracle oracle, List<String> thenLiterals, String runId) {
        Lock lock = new Lock();
        for (String lit : thenLiterals) {
            if (!AssertionScanner.missingThenLiterals(List.of(lit), oracle.tokens())
                    .contains(lit)) {
                lock.tokens.add(lit);
            }
        }
        lock.statements.addAll(lockedStatements(oracle.assertionStatements(), lock.tokens));
        lock.fingerprint = fingerprint(lock.statements);
        lock.watched = oracle.watchedHash();
        lock.reachedInRun = runId;
        return lock;
    }

    /**
     * Adds the THEN values the test asserts now but did not when the lock was taken (a value asserted after the first
     * run that reached the code is locked too); locked values are never dropped.
     */
    public static void extend(Lock lock, AssertionScanner.ScenarioOracle oracle, List<String> thenLiterals) {
        if (lock == null) {
            return;
        }
        Lock now = create(oracle, thenLiterals, lock.reachedInRun);
        now.tokens.stream().filter(t -> !lock.tokens.contains(t)).forEach(lock.tokens::add);
        now.statements.stream().filter(x -> !lock.statements.contains(x)).forEach(lock.statements::add);
        lock.fingerprint = fingerprint(lock.statements);
    }

    public static Check check(Lock lock, AssertionScanner.ScenarioOracle current) {
        if (lock == null) {
            return new Check(Verdict.OK, List.of());
        }
        List<String> missing = new ArrayList<>();
        for (String token : lock.tokens) {
            if (AssertionScanner.missingThenLiterals(List.of(token), current.tokens())
                    .contains(token)) {
                missing.add(token);
            }
        }
        if (!missing.isEmpty()) {
            return new Check(Verdict.MODIFIED, missing);
        }
        Set<String> now = Set.copyOf(current.assertionStatements());
        boolean allStatementsKept = now.containsAll(lock.statements);
        return new Check(allStatementsKept ? Verdict.OK : Verdict.STRUCTURAL_CHANGE, List.of());
    }

    /**
     * The assertion statements that carry a locked value. Statements are stored without whitespace (see
     * {@link AssertionScanner}), so a value is compared without it too: {@code `Order not found`} locks
     * {@code isEqualTo("Order not found")}. A value may also be written by a name ({@code isNotFound()} or
     * {@code HttpStatus.NOT_FOUND} for {@code 404}), matched as a whole word, and a number only as a whole number.
     */
    public static List<String> lockedStatements(List<String> statements, List<String> tokens) {
        List<String> values = tokens.stream()
                .map(t -> t.replaceAll("\\s+", ""))
                .filter(t -> !t.isEmpty())
                .toList();
        List<Pattern> names = tokens.stream()
                .flatMap(t -> AssertionScanner.namesOf(t).stream())
                .map(n -> Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(n) + "(?![A-Za-z0-9_])"))
                .toList();
        return statements.stream()
                .filter(stmt -> values.stream().anyMatch(v -> AssertionScanner.holds(stmt, v))
                        || names.stream().anyMatch(n -> n.matcher(stmt).find()))
                .toList();
    }

    public static String fingerprint(List<String> statements) {
        return Hashes.shortSha(String.join("\n", statements.stream().sorted().toList()));
    }
}
