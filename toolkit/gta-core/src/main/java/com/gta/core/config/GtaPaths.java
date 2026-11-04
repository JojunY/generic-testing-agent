package com.gta.core.config;

import java.nio.file.Path;

/** All well-known locations inside a service repository (plan §5.2, §10.1). */
public record GtaPaths(Path root) {
    /** Maven source layout, relative to the module root (forward slashes, as git reports paths). */
    public static final String MAIN_JAVA = "src/main/java";
    public static final String MAIN_RESOURCES = "src/main/resources";
    public static final String TEST_JAVA = "src/test/java";
    public static final String TEST_RESOURCES = "src/test/resources";

    public Path mainJava() {
        return root.resolve(MAIN_JAVA);
    }

    public Path mainResources() {
        return root.resolve(MAIN_RESOURCES);
    }

    public Path testJava() {
        return root.resolve(TEST_JAVA);
    }

    public Path testResources() {
        return root.resolve(TEST_RESOURCES);
    }

    /** Source file of a test class, e.g. com.acme.OrderIT -> src/test/java/com/acme/OrderIT.java. */
    public Path testSource(String fqcn) {
        return testJava().resolve(fqcn.replace('.', '/') + ".java");
    }

    /** Repository-relative path of a test class source, as used in ownsFiles. */
    public static String testSourceRel(String fqcn) {
        return TEST_JAVA + "/" + fqcn.replace('.', '/') + ".java";
    }

    /** Spec file location -> flow id (".gta/specs/order/create.spec.md" -> "order.create"); empty if it is not a spec. */
    public static java.util.Optional<String> flowIdOfSpecPath(String rel) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|.*/)\\.gta/specs/([a-z][a-z0-9-]*)/([a-z][a-z0-9-]*)\\.(?:spec\\.md|oracles\\.json)$")
                .matcher(rel.replace('\\', '/'));
        return m.matches() ? java.util.Optional.of(m.group(1) + "." + m.group(2)) : java.util.Optional.empty();
    }

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
