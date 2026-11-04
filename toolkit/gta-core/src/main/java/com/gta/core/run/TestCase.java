package com.gta.core.run;

import java.util.ArrayList;
import java.util.List;

/** One executed test (merged from Surefire XML and gta-junit results). */
public class TestCase {
    public String className;
    public String method;
    public String displayName;
    public String outcome; // passed | failed | error | skipped | known-defect
    public String failureType;
    public String message;
    public String stack;
    public List<String> tags = new ArrayList<>();
    /** Classification code (ErrorCode name) for non-passing tests. */
    public String code;

    public boolean sutReached;

    public boolean passed() {
        return "passed".equals(outcome) || "known-defect".equals(outcome);
    }

    public String scenarioId() {
        return tags.stream()
                .filter(t -> t.startsWith("scenario:"))
                .map(t -> t.substring("scenario:".length()))
                .findFirst()
                .orElse(null);
    }
}
