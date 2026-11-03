package com.gta.core.oracle;

import com.gta.core.state.WorkState;
import com.gta.core.util.Hashes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Monotonic oracle lock (plan §5.3 item 3): once the THEN-derived expected values are locked, the assertions carrying
 * them may not change or disappear; new assertions may be added. Inputs are watched, not locked.
 */
public final class OracleLock {
    public enum Verdict { OK, STRUCTURAL_CHANGE, MODIFIED }

    public record Check(Verdict verdict, List<String> missingTokens) {
    }

    private OracleLock() {
    }

    /** Creates the lock from the first run that reached the system under test. */
    public static WorkState.Lock create(AssertionScanner.ScenarioOracle oracle, List<String> thenLiterals, String runId) {
        WorkState.Lock lock = new WorkState.Lock();
        for (String lit : thenLiterals) {
            if (!AssertionScanner.missingThenLiterals(List.of(lit), oracle.tokens()).contains(lit)) {
                lock.tokens.add(lit);
            }
        }
        for (String stmt : oracle.assertionStatements()) {
            if (lock.tokens.stream().anyMatch(stmt::contains)) {
                lock.statements.add(stmt);
            }
        }
        lock.fingerprint = fingerprint(lock.statements);
        lock.watched = oracle.watchedHash();
        lock.reachedInRun = runId;
        return lock;
    }

    public static Check check(WorkState.Lock lock, AssertionScanner.ScenarioOracle current) {
        if (lock == null) {
            return new Check(Verdict.OK, List.of());
        }
        List<String> missing = new ArrayList<>();
        for (String token : lock.tokens) {
            if (AssertionScanner.missingThenLiterals(List.of(token), current.tokens()).contains(token)) {
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

    public static String fingerprint(List<String> statements) {
        return Hashes.shortSha(String.join("\n", statements.stream().sorted().toList()));
    }
}
