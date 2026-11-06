package com.gta.core.report;

import com.gta.core.spec.Spec;
import com.gta.core.util.AtomicFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The test case ledger of one flow, {@code .gta/specs/<domain>/<flow>.cases.md} (plan §10.6). It indexes the spec's
 * scenarios as test cases and their latest result; it never copies the spec's steps or expected values, which stay the
 * only source of truth. Rows are updated one by one, so a human's Status edit and the manual cases are kept.
 */
public final class CaseLedger {
    public static final String HEADER_MARKER =
            "<!-- gta:cases v1. Maintained by gtaw: edit only the Status column (Retired) and the Manual cases table. -->";
    public static final String NOT_RUN = "Not run";
    public static final String MANUAL = "Manual";
    public static final String NOT_AUTOMATED = "–";

    public static final String ACTIVE = "Active";
    public static final String NEEDS_REVIEW = "Needs Review";
    public static final String RETIRED = "Retired";
    public static final Set<String> STATUSES = Set.of(ACTIVE, NEEDS_REVIEW, RETIRED);

    static final List<String> COLUMNS = List.of(
            "Test Case ID",
            "Test Title",
            "Test Content",
            "Expect Result",
            "Linked Spec",
            "Automation",
            "Priority",
            "Test Result",
            "Status");
    static final List<String> MANUAL_COLUMNS =
            List.of("Test Case ID", "Test Title", "Test Content", "Expect Result", "Priority", "Test Result", "Status");
    private static final int SUMMARY_LENGTH = 100;
    private static final Pattern CELL_SPLIT = Pattern.compile("(?<!\\\\)\\|");

    /** One test case. Manual cases have no linked spec and {@code Manual} as automation. */
    public static final class Row {
        public String id;
        public String title = "";
        public String content = "";
        public String expect = "";
        public String linkedSpec = "";
        public String automation = NOT_AUTOMATED;
        public String priority = "P1";
        public String result = NOT_RUN;
        public String status = ACTIVE;

        Row copy() {
            Row r = new Row();
            r.id = id;
            r.title = title;
            r.content = content;
            r.expect = expect;
            r.linkedSpec = linkedSpec;
            r.automation = automation;
            r.priority = priority;
            r.result = result;
            r.status = status;
            return r;
        }

        boolean sameAs(Row o) {
            return id.equals(o.id)
                    && title.equals(o.title)
                    && content.equals(o.content)
                    && expect.equals(o.expect)
                    && linkedSpec.equals(o.linkedSpec)
                    && automation.equals(o.automation)
                    && priority.equals(o.priority)
                    && result.equals(o.result)
                    && status.equals(o.status);
        }
    }

    /** What changed in one row, for the run report. */
    public record Change(String id, String before, String after) {}

    private final String flowId;
    private String specFile = "";
    private String specRev = "";
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private final Map<String, Row> manual = new LinkedHashMap<>();
    private final List<Change> changes = new ArrayList<>();

    public CaseLedger(String flowId) {
        this.flowId = flowId;
    }

    public String flowId() {
        return flowId;
    }

    public List<Row> rows() {
        return List.copyOf(rows.values());
    }

    public List<Row> manualRows() {
        return List.copyOf(manual.values());
    }

    public Optional<Row> row(String id) {
        return Optional.ofNullable(rows.get(id));
    }

    /** Row changes since the ledger was read. */
    public List<Change> changes() {
        return List.copyOf(changes);
    }

    // ------------------------------------------------------------------ maintenance hooks

    /**
     * Brings the rows in line with an approved spec: a new scenario gets a row (Not run); a scenario whose locked test was
     * written for another revision needs review; a scenario that left the spec is retired, and its test keeps running
     * until a human confirms the deletion.
     *
     * @param lockedRevisions scenario id → revision its committed test was locked on (from the oracle shard)
     */
    public void syncWithSpec(Spec spec, String specFileName, Map<String, String> lockedRevisions) {
        specFile = specFileName;
        specRev = spec.rev() == null ? "" : spec.rev();
        List<String> inSpec = new ArrayList<>();
        for (Spec.Requirement r : spec.requirements) {
            for (Spec.Scenario s : r.scenarios) {
                if (s.id() == null) {
                    continue;
                }
                inSpec.add(s.id());
                update(s.id(), row -> {
                    row.title = s.title == null ? "" : s.title.strip();
                    row.content = summary(s.whens);
                    row.expect = summary(s.thens);
                    row.linkedSpec = specFileName + "#" + s.id().substring(s.id().indexOf('/') + 1);
                    row.priority = s.priority();
                    String locked = lockedRevisions.get(s.id());
                    if (RETIRED.equals(row.status)) {
                        return; // a human retired it: the spec change does not bring it back
                    }
                    row.status = locked != null && !locked.equals(s.revision()) ? NEEDS_REVIEW : row.status;
                });
            }
        }
        for (Row row : List.copyOf(rows.values())) {
            if (!inSpec.contains(row.id) && !RETIRED.equals(row.status)) {
                update(row.id, r -> r.status = RETIRED);
            }
        }
    }

    /** A test of this case was run: its latest result, and the test that automates it. */
    public void recordResult(String id, String automation, String result, String status) {
        if (!rows.containsKey(id)) {
            return; // not a scenario of the approved spec (e.g. a foundation test)
        }
        update(id, row -> {
            if (automation != null && !automation.isBlank()) {
                row.automation = automation;
            }
            row.result = result;
            if (status != null && !RETIRED.equals(row.status)) {
                row.status = status;
            }
        });
    }

    /** Marks a case for review (code it covers changed); retired cases stay retired. */
    public boolean markForReview(String id) {
        Row row = rows.get(id);
        if (row == null || !ACTIVE.equals(row.status)) {
            return false;
        }
        update(id, r -> r.status = NEEDS_REVIEW);
        return true;
    }

    public void remove(String id) {
        Row row = rows.remove(id);
        if (row != null) {
            changes.add(new Change(id, describe(row), "deleted"));
        }
    }

    private void update(String id, Consumer<Row> change) {
        Row row = rows.get(id);
        Row before = row == null ? null : row.copy();
        if (row == null) {
            row = new Row();
            row.id = id;
            rows.put(id, row);
        }
        change.accept(row);
        if (before == null) {
            changes.add(new Change(id, "new", describe(row)));
        } else if (!before.sameAs(row)) {
            changes.add(new Change(id, describe(before), describe(row)));
        }
    }

    private static String describe(Row r) {
        return r.status + ", " + r.result;
    }

    /** A one-sentence summary of a scenario's clauses, never their full text. */
    static String summary(List<String> clauses) {
        if (clauses.isEmpty()) {
            return "";
        }
        String first = clauses.get(0).strip().replaceAll("\\s+", " ");
        if (first.length() > SUMMARY_LENGTH) {
            first = first.substring(0, SUMMARY_LENGTH - 1).strip() + "…";
        }
        return clauses.size() > 1 ? first + " (+" + (clauses.size() - 1) + " more)" : first;
    }

    /** P0 core path, P1 important branch, P2 edge case; the analyst sets {@code priority=} per scenario, default P1. */
    /** {@code Pass 2026-11-04 a1b2c3d}: the latest result with its date and code version. */
    public static String result(boolean passed, String date, String sha7) {
        return (passed ? "Pass " : "Fail ") + date + " " + sha7;
    }

    // ------------------------------------------------------------------ file format

    public static CaseLedger read(Path file, String flowId) {
        if (!Files.exists(file)) {
            return new CaseLedger(flowId);
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8), flowId);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void write(Path file) {
        AtomicFiles.write(file, format());
    }

    static CaseLedger parse(String text, String flowId) {
        CaseLedger l = new CaseLedger(flowId);
        boolean inManual = false;
        for (String line : text.split("\\R")) {
            String t = line.strip();
            if (t.startsWith("## ")) {
                inManual = t.toLowerCase(Locale.ROOT).startsWith("## manual");
                continue;
            }
            if (t.startsWith("Spec: ")) {
                int rev = t.lastIndexOf("· rev ");
                l.specRev = rev < 0 ? "" : t.substring(rev + 6).strip();
                int open = t.indexOf('[');
                int close = t.indexOf(']');
                l.specFile = open >= 0 && close > open ? t.substring(open + 1, close) : "";
                continue;
            }
            if (!t.startsWith("|") || t.startsWith("|---") || t.startsWith("| Test Case ID")) {
                continue;
            }
            List<String> cells = cells(t);
            Row r = new Row();
            if (inManual && cells.size() >= MANUAL_COLUMNS.size()) {
                r.id = cells.get(0);
                r.title = cells.get(1);
                r.content = cells.get(2);
                r.expect = cells.get(3);
                r.automation = MANUAL;
                r.priority = cells.get(4);
                r.result = cells.get(5);
                r.status = cells.get(6);
                l.manual.put(r.id, r);
            } else if (!inManual && cells.size() >= COLUMNS.size()) {
                r.id = cells.get(0);
                r.title = cells.get(1);
                r.content = cells.get(2);
                r.expect = cells.get(3);
                r.linkedSpec = cells.get(4);
                r.automation = cells.get(5);
                r.priority = cells.get(6);
                r.result = cells.get(7);
                r.status = cells.get(8);
                l.rows.put(r.id, r);
            }
        }
        return l;
    }

    private static List<String> cells(String line) {
        String inner = line.substring(1, line.endsWith("|") && line.length() > 1 ? line.length() - 1 : line.length());
        List<String> out = new ArrayList<>();
        for (String c : CELL_SPLIT.split(inner, -1)) {
            out.add(c.strip().replace("\\|", "|"));
        }
        return out;
    }

    public String format() {
        StringBuilder b = new StringBuilder();
        b.append("# Test cases: ").append(flowId).append("\n\n");
        b.append(HEADER_MARKER).append('\n');
        b.append("Spec: [")
                .append(specFile)
                .append("](")
                .append(specFile)
                .append(") · rev ")
                .append(specRev);
        b.append("\n\n");
        table(
                b,
                COLUMNS,
                rows.values(),
                r -> List.of(
                        r.id,
                        r.title,
                        r.content,
                        r.expect,
                        r.linkedSpec,
                        r.automation,
                        r.priority,
                        r.result,
                        r.status));
        b.append("\n## Manual cases\n\n");
        table(
                b,
                MANUAL_COLUMNS,
                manual.values(),
                r -> List.of(r.id, r.title, r.content, r.expect, r.priority, r.result, r.status));
        return b.toString();
    }

    private static void table(
            StringBuilder b,
            List<String> columns,
            Iterable<Row> rows,
            java.util.function.Function<Row, List<String>> cells) {
        b.append("| ").append(String.join(" | ", columns)).append(" |\n");
        b.append("|").append("---|".repeat(columns.size())).append('\n');
        for (Row r : rows) {
            b.append("| ")
                    .append(cells.apply(r).stream()
                            .map(c -> c.replace("|", "\\|").replace('\n', ' '))
                            .collect(Collectors.joining(" | ")))
                    .append(" |\n");
        }
    }

    /** {@code .gta/specs/INDEX.md}: one line per flow with its case counts (the coverage matrix). Derived: rewritten whole. */
    public static String index(List<CaseLedger> ledgers) {
        StringBuilder b = new StringBuilder("# Test cases: overview\n\n");
        b.append("<!-- gta:cases-index v1. Generated by gtaw from the *.cases.md files; do not edit. -->\n\n");
        b.append("| Flow | Scenarios | Automated | Manual | Pass | Fail | Needs Review | Retired | P0 pass |\n");
        b.append("|---|---|---|---|---|---|---|---|---|\n");
        for (CaseLedger l : ledgers) {
            List<Row> rs = l.rows();
            long automated =
                    rs.stream().filter(r -> !NOT_AUTOMATED.equals(r.automation)).count();
            long pass = rs.stream().filter(r -> r.result.startsWith("Pass")).count();
            long fail = rs.stream().filter(r -> r.result.startsWith("Fail")).count();
            long review = rs.stream().filter(r -> NEEDS_REVIEW.equals(r.status)).count();
            long retired = rs.stream().filter(r -> RETIRED.equals(r.status)).count();
            long p0 = rs.stream()
                    .filter(r -> "P0".equals(r.priority) && !RETIRED.equals(r.status))
                    .count();
            long p0Pass = rs.stream()
                    .filter(r -> "P0".equals(r.priority) && !RETIRED.equals(r.status) && r.result.startsWith("Pass"))
                    .count();
            String dir = l.flowId.substring(0, l.flowId.indexOf('.'));
            String file = l.flowId.substring(l.flowId.indexOf('.') + 1) + ".cases.md";
            b.append("| [")
                    .append(l.flowId)
                    .append("](")
                    .append(dir)
                    .append('/')
                    .append(file)
                    .append(") | ")
                    .append(rs.size())
                    .append(" | ")
                    .append(automated)
                    .append(" | ")
                    .append(l.manual.size())
                    .append(" | ")
                    .append(pass)
                    .append(" | ")
                    .append(fail)
                    .append(" | ")
                    .append(review)
                    .append(" | ")
                    .append(retired)
                    .append(" | ")
                    .append(p0Pass)
                    .append('/')
                    .append(p0)
                    .append(" |\n");
        }
        return b.toString();
    }
}
