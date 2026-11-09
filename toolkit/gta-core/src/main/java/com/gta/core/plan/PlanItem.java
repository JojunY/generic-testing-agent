package com.gta.core.plan;

import java.util.ArrayList;
import java.util.List;

/** One unit of test work, derived deterministically from an approved scenario (plan §10.2). */
public class PlanItem {
    public String id;
    public String kind; // foundation | test
    public String ref; // scenarioId, invariantId or domain
    public String flowId;
    public String title;
    public String capability;
    public String layer;
    public String intent; // hardening | characterization | catching | reproducing
    public String agent = "gta-test-author";
    public List<String> skills = new ArrayList<>();
    public Oracle oracle = new Oracle();
    public String testClass;
    /**
     * A Cucumber scenario's feature file (module-relative) and its step definitions class; null for a JUnit test. The
     * test class is then the service's Cucumber suite, which runs the scenario (plan §7.10).
     */
    public String feature;

    public String steps;
    public List<String> ownsFiles = new ArrayList<>();
    public List<String> dependsOn = new ArrayList<>();
    public String priority;
    public String scenarioRevision;
    public String fidelity = "high";

    public static class Oracle {
        public String type = "state";
        public String source;
        public List<String> then = new ArrayList<>();
        public boolean reviewerChecked;
    }

    public boolean isFoundation() {
        return "foundation".equals(kind);
    }

    /** What people read: the scenario id, or the item id of a seed test (which has no scenario). */
    public String label() {
        return isFoundation() ? id : ref;
    }

    public String domain() {
        return flowId.substring(0, flowId.indexOf('.'));
    }
}
