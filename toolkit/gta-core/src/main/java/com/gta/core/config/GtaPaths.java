package com.gta.core.config;

import java.nio.file.Path;

/** All well-known locations inside a service repository (plan §5.2, §10.1). */
public record GtaPaths(Path root) {
    public Path gta() {
        return root.resolve(".gta");
    }

    public Path config() {
        return gta().resolve("config.json");
    }

    public Path waivers() {
        return gta().resolve("waivers.json");
    }

    public Path managedFiles() {
        return gta().resolve("managed-files.json");
    }

    public Path wrapperProperties() {
        return gta().resolve("wrapper").resolve("gta-wrapper.properties");
    }

    public Path specs() {
        return gta().resolve("specs");
    }

    public Path skills() {
        return gta().resolve("skills");
    }

    public Path escalations() {
        return gta().resolve("escalations");
    }

    public Path spec(String flowId) {
        int dot = flowId.indexOf('.');
        return specs().resolve(flowId.substring(0, dot)).resolve(flowId.substring(dot + 1) + ".spec.md");
    }

    public Path oracleShard(String flowId) {
        int dot = flowId.indexOf('.');
        return specs().resolve(flowId.substring(0, dot)).resolve(flowId.substring(dot + 1) + ".oracles.json");
    }

    public Path work() {
        return gta().resolve("work");
    }

    public Path state() {
        return work().resolve("state.json");
    }

    public Path plan() {
        return work().resolve("plan.json");
    }

    public Path profile() {
        return work().resolve("service-profile.json");
    }

    public Path flowCatalog() {
        return work().resolve("flow-catalog.json");
    }

    public Path briefs() {
        return work().resolve("briefs");
    }

    public Path runs() {
        return work().resolve("runs");
    }

    public Path recover() {
        return work().resolve("recover");
    }

    public Path session() {
        return work().resolve(".session");
    }

    public Path lock() {
        return work().resolve(".lock");
    }

    /** Repo-relative path with forward slashes. */
    public String rel(Path p) {
        return root.toAbsolutePath().normalize().relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
