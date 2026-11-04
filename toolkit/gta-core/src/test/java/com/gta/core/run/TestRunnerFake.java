package com.gta.core.run;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/** A {@link TestRunner} for tests of gta: returns the queued results in order; the last one repeats. */
public final class TestRunnerFake implements TestRunner {
    private final Deque<RunResult> results = new ArrayDeque<>();
    private final List<Options> calls = new ArrayList<>();

    public TestRunnerFake then(RunResult r) {
        results.add(r);
        return this;
    }

    /** How many runs were made. */
    public int runs() {
        return calls.size();
    }

    /** The options of every run, in order. */
    public List<Options> calls() {
        return calls;
    }

    @Override
    public RunResult run(
            String runId, String itemId, Options options, Set<String> productionClasses, Set<String> testClasses) {
        calls.add(options);
        RunResult r = results.size() > 1 ? results.poll() : results.peek();
        if (r == null) {
            r = new RunResult();
        }
        r.runId = runId;
        r.itemId = itemId;
        return r;
    }
}
