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
    public String basePackage;
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
