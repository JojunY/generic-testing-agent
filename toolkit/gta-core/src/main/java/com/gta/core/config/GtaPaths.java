package com.gta.core.config;

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * All well-known locations inside a service repository (plan §5.2, §10.1). {@code root} is the repository root, where
 * git and {@code .gta/} live. {@code module} is the Maven module that holds the service ({@code build.module}, plan §15
 * P1), "" for a single-module repository: sources, tests and build output resolve under it.
 */
public record GtaPaths(Path root, String module) {
    /** Maven source layout, relative to the module root (forward slashes, as git reports paths). */
    public static final String MAIN_JAVA = "src/main/java";

    public static final String MAIN_RESOURCES = "src/main/resources";
    public static final String TEST_JAVA = "src/test/java";
    public static final String TEST_RESOURCES = "src/test/resources";

    public GtaPaths {
        module = module == null ? "" : module.replace('\\', '/').replaceAll("^/+|/+$", "");
    }

    public GtaPaths(Path root) {
        this(root, "");
    }

    /** The same repository with the service in {@code module}. */
    public GtaPaths withModule(String module) {
        return new GtaPaths(root, module);
    }

    /** Directory of the service's own pom.xml and source tree. */
    public Path moduleRoot() {
        return module.isEmpty() ? root : root.resolve(module);
    }

    /** A module-relative path (src/test/java/...) as a repository-relative one, as git and ownsFiles use them. */
    public String inModule(String moduleRelative) {
        return inModule(module, moduleRelative);
    }

    public static String inModule(String module, String moduleRelative) {
        return module == null || module.isEmpty() ? moduleRelative : module + "/" + moduleRelative;
    }

    public Path mainJava() {
        return moduleRoot().resolve(MAIN_JAVA);
    }

    public Path mainResources() {
        return moduleRoot().resolve(MAIN_RESOURCES);
    }

    public Path testJava() {
        return moduleRoot().resolve(TEST_JAVA);
    }

    public Path testResources() {
        return moduleRoot().resolve(TEST_RESOURCES);
    }

    /** Surefire's report directory of the service module. */
    public Path surefireReports() {
        return moduleRoot().resolve("target/surefire-reports");
    }

    /** Source file of a test class, e.g. com.acme.OrderIT -> src/test/java/com/acme/OrderIT.java. */
    public Path testSource(String fqcn) {
        return testJava().resolve(fqcn.replace('.', '/') + ".java");
    }

    /** Repository-relative path of a test class source, as used in ownsFiles and shown to people. */
    public String testSourceRel(String fqcn) {
        return testSourceRel(module, fqcn);
    }

    public static String testSourceRel(String module, String fqcn) {
        return inModule(module, TEST_JAVA + "/" + fqcn.replace('.', '/') + ".java");
    }

    private static final Pattern SPEC_PATH = Pattern.compile(
            "(?:^|.*/)\\.gta/specs/([a-z][a-z0-9-]*)/([a-z][a-z0-9-]*)\\.(?:spec\\.md|oracles\\.json)$");

    /** Spec file location -> flow id (".gta/specs/order/create.spec.md" -> "order.create"); empty if it is not a spec. */
    public static Optional<String> flowIdOfSpecPath(String rel) {
        Matcher m = SPEC_PATH.matcher(rel.replace('\\', '/'));
        return m.matches() ? Optional.of(m.group(1) + "." + m.group(2)) : Optional.empty();
    }

    private static final Pattern CASES_PATH =
            Pattern.compile("(?:^|.*/)\\.gta/specs/([a-z][a-z0-9-]*)/([a-z][a-z0-9-]*)\\.cases\\.md$");

    /** Ledger location -> flow id (".gta/specs/order/create.cases.md" -> "order.create"). */
    public static Optional<String> flowIdOfCasesPath(String rel) {
        Matcher m = CASES_PATH.matcher(rel.replace('\\', '/'));
        return m.matches() ? Optional.of(m.group(1) + "." + m.group(2)) : Optional.empty();
    }

    public Path gta() {
        return root.resolve(".gta");
    }

    public Path config() {
        return gta().resolve("config.json");
    }

    /** Lessons from earlier runs (plan §12, the learning loop); tracked. */
    public Path lessons() {
        return gta().resolve("lessons.md");
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

    /** The incident reports of a flow (incident mode), tracked next to its spec so scenarios can cite them. */
    public Path incidents(String flowId) {
        Path spec = spec(flowId);
        String name = String.valueOf(spec.getFileName());
        return spec.resolveSibling(name.substring(0, name.length() - ".spec.md".length()) + ".incidents.md");
    }

    public Path spec(String flowId) {
        int dot = flowId.indexOf('.');
        return specs().resolve(flowId.substring(0, dot)).resolve(flowId.substring(dot + 1) + ".spec.md");
    }

    public Path oracleShard(String flowId) {
        int dot = flowId.indexOf('.');
        return specs().resolve(flowId.substring(0, dot)).resolve(flowId.substring(dot + 1) + ".oracles.json");
    }

    /** Test case ledger of a flow, next to its spec (plan §10.6). */
    public Path cases(String flowId) {
        int dot = flowId.indexOf('.');
        return specs().resolve(flowId.substring(0, dot)).resolve(flowId.substring(dot + 1) + ".cases.md");
    }

    /** Overview of every ledger with its coverage matrix. */
    public Path casesIndex() {
        return specs().resolve("INDEX.md");
    }

    /** Session reports, tracked: {@code <yyyy-MM-dd>-<sessionId>.md}. */
    public Path reports() {
        return gta().resolve("reports");
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
        return moduleRoot().resolve("target/gta-results");
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
