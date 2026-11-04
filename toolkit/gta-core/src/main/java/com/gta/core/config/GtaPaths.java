package com.gta.core.config;

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern SPEC_PATH = Pattern.compile(
            "(?:^|.*/)\\.gta/specs/([a-z][a-z0-9-]*)/([a-z][a-z0-9-]*)\\.(?:spec\\.md|oracles\\.json)$");

    /** Spec file location -> flow id (".gta/specs/order/create.spec.md" -> "order.create"); empty if it is not a spec. */
    public static Optional<String> flowIdOfSpecPath(String rel) {
        Matcher m = SPEC_PATH.matcher(rel.replace('\\', '/'));
        return m.matches() ? Optional.of(m.group(1) + "." + m.group(2)) : Optional.empty();
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

    /** SHAs of the commits gtaw made (see CommitLedger). */
    public Path commitLedger() {
        return work().resolve("gtaw-commits.txt");
    }

    /** Copies of gtaw-owned spec fields, taken after every gtaw write (owned-field check). */
    public Path snapshots() {
        return work().resolve("snapshots");
    }

    /** Where gta-junit writes scenario results during a test run. */
    public Path gtaResults() {
        return root.resolve("target/gta-results");
    }

    public Path verifyLatest() {
        return runs().resolve("verify-latest.json");
    }

    public Path gateLatest() {
        return runs().resolve("gate-latest.json");
    }

    public Path runs() {
        return work().resolve("runs");
    }

    public Path recover() {
        return work().resolve("recover");
    }

    public Path lock() {
        return work().resolve(".lock");
    }

    /** Repo-relative path with forward slashes. */
    public String rel(Path p) {
        return root.toAbsolutePath()
                .normalize()
                .relativize(p.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }
}
