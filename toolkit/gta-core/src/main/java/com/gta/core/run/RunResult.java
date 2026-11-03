package com.gta.core.run;

import java.util.ArrayList;
import java.util.List;

/** Result of one gtaw-controlled test execution (stored in .gta/work/runs/&lt;runId&gt;/run.json). */
public class RunResult {
    public String schemaVersion = "1.0";
    public String runId;
    public String itemId;
    public List<String> command = new ArrayList<>();
    public int exitCode;
    public boolean compileError;
    public String compileMessage;
    public long durationMs;
    public List<TestCase> cases = new ArrayList<>();
    public String logFile;

    public boolean allPassed() {
        return !compileError && !cases.isEmpty() && cases.stream().allMatch(TestCase::passed);
    }

    public List<TestCase> failures() {
        return cases.stream().filter(c -> !c.passed() && !"skipped".equals(c.outcome)).toList();
    }
}
