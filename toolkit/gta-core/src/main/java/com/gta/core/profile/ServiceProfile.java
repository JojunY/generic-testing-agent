package com.gta.core.profile;

import java.util.ArrayList;
import java.util.List;

/** Output of the deterministic profile step (stored in .gta/work/service-profile.json). */
public class ServiceProfile {
    public String schemaVersion = "1.0";
    public String artifactId;
    public String bootVersion;
    public int bootMajor;
    public int junitMajor;
    /** The service's Java release (17, 21, …); 0 when its pom does not say. */
    public int javaMajor;

    public String basePackage;
    /** The @SpringBootApplication class, or null when there is none. */
    public String applicationClass;

    public boolean mavenWrapper;
    public List<String> dependencies = new ArrayList<>();
    public List<String> capabilities = new ArrayList<>();
    public List<String> explainOnlyCapabilities = new ArrayList<>();
    public List<EntryPoint> entrypoints = new ArrayList<>();

    public static class EntryPoint {
        public String flowId;
        public String capability;
        public String kind;
        public String ref;
        public String httpMethod;
        public String path;
        public String trigger;
        public int risk;
    }
}
