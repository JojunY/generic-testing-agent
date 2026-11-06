package com.gta.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gta.core.config.GtaPaths;
import com.gta.core.engine.Engine;
import com.gta.core.error.ErrorCode;
import com.gta.core.error.GtaException;
import com.gta.core.gate.Gate;
import com.gta.core.run.RunResult;
import com.gta.core.util.Json;
import com.gta.core.util.ProcessRunner;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * gtaw — the deterministic side of gta (plan §11.2). Every command prints one JSON object (or human text with
 * --format human) and exits with the category exit code of plan §5.6.
 */
@Command(
        name = "gtaw",
        mixinStandardHelpOptions = true,
        versionProvider = GtaCli.Version.class,
        description = "Generic Testing Agent toolkit: spec-first test harness for Java/Spring services.",
        subcommands = {
            GtaCli.Init.class,
            GtaCli.Upgrade.class,
            GtaCli.Doctor.class,
            GtaCli.Next.class,
            GtaCli.Check.class,
            GtaCli.Submit.class,
            GtaCli.Answer.class,
            GtaCli.Note.class,
            GtaCli.Status.class,
            GtaCli.Abort.class,
            GtaCli.Run.class,
            GtaCli.Verify.class,
            GtaCli.Mutate.class,
            GtaCli.GateCmd.class,
            GtaCli.Relock.class,
            GtaCli.Report.class,
            GtaCli.Cases.class,
            GtaCli.Impact.class,
            GtaCli.Env.class
        })
public final class GtaCli implements Callable<Integer> {
    @Option(
            names = "--root",
            description = "Service repository root (default: current directory)",
            scope = CommandLine.ScopeType.INHERIT)
    Path root = Path.of(".");

    @Option(names = "--format", description = "json | human (default: json)", scope = CommandLine.ScopeType.INHERIT)
    String format = "json";

    public static void main(String[] args) {
        // JSON and prompts are UTF-8 regardless of the console code page (Windows cp1252/cp936), so agents read them
        // intact.
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        System.exit(commandLine().execute(args));
    }

    /** The command line with gtaw's error contract: every failure is one JSON error object and its exit code. */
    static CommandLine commandLine() {
        CommandLine cli = new CommandLine(new GtaCli());
        cli.setExecutionExceptionHandler((ex, cmd, parsed) -> {
            GtaException ge = ex instanceof GtaException g
                    ? g
                    : new GtaException(
                            ErrorCode.INTERNAL_ERROR,
                            String.valueOf(ex),
                            "This is a gtaw bug; keep .gta/work/ and report it",
                            ex);
            System.out.println(Json.write(ge.toErrorObject()));
            return ge.code().exitCode();
        });
        cli.setParameterExceptionHandler((ex, args2) -> {
            GtaException ge = new GtaException(
                    ErrorCode.USAGE_INVALID_ARGUMENT,
                    ex.getMessage(),
                    "Run ./gtaw " + String.join(" ", args2.length > 0 ? args2[0] : "") + " --help");
            System.out.println(Json.write(ge.toErrorObject()));
            return ErrorCode.USAGE_INVALID_ARGUMENT.exitCode();
        });
        return cli;
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    static int print(GtaCli parent, Object value) {
        if ("human".equals(parent.format) && value instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String s && s.contains("\n")) {
                    System.out.println(e.getKey() + ":\n" + s);
                } else if (v instanceof List<?> list) {
                    System.out.println(e.getKey() + ":" + (list.isEmpty() ? " none" : ""));
                    for (Object x : list) {
                        System.out.println("  - " + human(x));
                    }
                } else {
                    System.out.println(e.getKey() + ": " + human(v));
                }
            }
        } else if (value instanceof String s) {
            System.out.println(s);
        } else {
            System.out.println(Json.write(value));
        }
        return 0;
    }

    /** One line per object: "id  status  key=value ..." for maps, plain text otherwise. */
    static String human(Object v) {
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder();
            for (String lead : List.of("id", "flowId", "itemId")) {
                if (m.get(lead) != null) {
                    sb.append(m.get(lead));
                    break;
                }
            }
            if (m.get("status") != null) {
                sb.append(sb.length() > 0 ? "  " : "").append(m.get("status"));
            }
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (List.of("id", "flowId", "itemId", "status").contains(k) || e.getValue() == null) {
                    continue;
                }
                sb.append(sb.length() > 0 ? "  " : "")
                        .append(k)
                        .append('=')
                        .append(
                                e.getValue() instanceof String str
                                        ? str
                                        : Json.write(e.getValue())
                                                .replace("\n", "")
                                                .replaceAll("\\s+", " "));
            }
            return sb.toString();
        }
        return v instanceof String str ? str : Json.write(v).replace("\n", "").replaceAll("\\s+", " ");
    }

    static Engine engine(GtaCli parent) {
        return new Engine(parent.root);
    }

    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {"gta-cli " + version()};
        }
    }

    static String version() {
        try (InputStream in = GtaCli.class.getResourceAsStream("/gta/version.properties")) {
            Properties p = new Properties();
            if (in != null) {
                p.load(in);
            }
            return p.getProperty("version", "dev");
        } catch (IOException e) {
            return "dev";
        }
    }

    // ------------------------------------------------------------------ setup

    @Command(
            name = "init",
            description = "Install the gta harness for one platform into the repository (tech lead, once).")
    static final class Init implements Callable<Integer> {
        @CommandLine.ParentCommand
        GtaCli parent;

        @Option(names = "--platform", required = true, description = "copilot | opencode")
        String platform;

        @Option(names = "--distribution-url", description = "Nexus URL of gta-cli-<version>.jar for the wrapper")
        String distributionUrl;

        @Option(names = "--distribution-sha256", description = "sha256 of the jar at --distribution-url")
        String distributionSha;

        @Option(names = "--ci", description = "Also install the CI gate workflow .github/workflows/gta-gate.yml")
        boolean ci;

        @Override
        public Integer call() {
            return print(
                    parent, new Installer(parent.root).install(platform, distributionUrl, distributionSha, false, ci));
        }
    }

    @Command(
            name = "upgrade",
            description = "Re-install managed harness files; locally modified files are reported, not overwritten.")
    static final class Upgrade implements Callable<Integer> {
        @CommandLine.ParentCommand
        GtaCli parent;

        @Option(names = "--platform", description = "copilot | opencode (default: the installed one)")
        String platform;

        @Override
        public Integer call() {
            return print(parent, new Installer(parent.root).install(platform, null, null, true));
        }
    }

    @Command(name = "doctor", description = "Check JDK, Maven, git, harness files, emulators and IDE settings.")
    static final class Doctor implements Callable<Integer> {
        @CommandLine.ParentCommand
        GtaCli parent;

        @Override
        public Integer call() {
            Map<String, Object> report = new DoctorChecks(parent.root).run();
            print(parent, report);
            return "fail".equals(report.get("overall")) ? ErrorCode.ENV_CHECK_FAILED.exitCode() : 0;
        }
    }

    // ------------------------------------------------------------------ loop

    @Command(name = "next", description = "Run all deterministic steps and return the next agent or human node.")
    static final class Next implements Callable<Integer> {
        @CommandLine.ParentCommand
        GtaCli parent;

        @Option(names = "--mode", description = "service | explain | flow | pr | incident")
        String mode;

        @Option(names = "--target", description = "flowId (e.g. order.create) or a source file/class name")
        String target;

        @Option(names = "--base", description = "base ref for the session-end gate (default origin/main)")
        String base;

        @Option(names = "--takeover", description = "Take over the session of another chat (human only)")
        boolean takeover;

        @Option(
                names = "--intent",
                description = "PR mode: the PR description; incident mode: the incident report (what happened, what was"
                        + " expected). Both are documented intent for the analyst")
        String intent;

        @Override
        public Integer call() {
            return print(parent, engine(parent).next(mode, target, base, takeover, intent));
        }
    }

    @Command(name = "check", description = "Compile and run the item's tests once; free (does not use an attempt).")
    static final class Check implements Callable<Integer> {
        @CommandLine.ParentCommand
        GtaCli parent;

        @Option(names = "--item", required = true)
        String item;

        @Option(names = "--token", required = true)
        String token;

        @Override
        public Integer call() {
            return print(parent, engine(parent).check(item, token));
        }
    }

    @Command(name = "submit", description = "Submit an item (--item), a spec (--spec) or a review verdict (--review).")
    static final class Submit implements Callable<Integer> {
        @CommandLine.ParentCommand
        GtaCli parent;

        @Option(names = "--item")
        String item;

        @Option(names = "--spec")
        String spec;

        @Option(names = "--review")
        String review;

        @Option(names = "--token")
        String token;

        @Option(names = "--verdict")
        String verdict;

        @Option(names = "--reason")
        String reason = "";

        @Option(names = "--question", description = "With --item: ask a person instead of guessing (plan §5.10)")
        String question;

        @Option(names = "--recommend", description = "With --question: the answer you would pick")
        String recommend;

        @Override
        public Integer call() {
            Engine e = engine(parent);
            if (item != null && question != null) {
                return print(parent, e.submitQuestion(item, require(token, "--token"), question, recommend));
            }
            if (item != null) {
                return print(parent, e.submitItem(item, require(token, "--token")));
            }
            if (spec != null) {
                return print(parent, e.submitSpec(spec, require(token, "--token")));
            }
            if (review != null) {
                return print(
                        parent,
                        e.submitReview(review, require(token, "--token"), require(verdict, "--verdict"), reason));
            }
            throw new GtaException(
                    ErrorCode.USAGE_INVALID_ARGUMENT, "Nothing to submit", "Use --item, --spec or --review");
        }
    }

    @Command(
            name = "answer",
            description = "Record the human's reply verbatim (answers, approval or a decision letter).")
    static final class Answer implements Callable<Integer> {
        @CommandLine.ParentCommand
        GtaCli parent;

        @Parameters(arity = "0..*", description = "The human's reply, verbatim")
        List<String> words;

        @Option(
                names = "--file",
                description = "Read the reply from a UTF-8 file (use when the terminal mangles non-ASCII text)")
        Path file;

        @Override
        public Integer call() throws IOException {
            String reply;
            if (file != null) {
                reply = Files.readString(file, StandardCharsets.UTF_8).strip();
            } else if (words != null && !words.isEmpty()) {
                reply = String.join(" ", words);
            } else {
                throw new GtaException(
                        ErrorCode.USAGE_INVALID_ARGUMENT,
                        "No reply given",
                        "Run ./gtaw answer \"<reply>\" or ./gtaw answer --file <path>");
            }
            return print(parent, engine(parent).answer(reply));
        }
    }

    @Command(name = "note", description = "Tell gta something about the service; it is kept in .gta/notes.md.")
    static final class Note implements Callable<Integer> {
        @CommandLine.ParentCommand
        GtaCli parent;

        @Parameters(arity = "0..*", description = "The note, verbatim")
        List<String> words;

        @Option(names = "--file", description = "Read the note from a UTF-8 file")
        Path file;

        @Override
        public Integer call() throws IOException {
            String text = file != null
                    ? Files.readString(file, StandardCharsets.UTF_8)
                    : words == null ? "" : String.join(" ", words);
            return print(parent, engine(parent).note(text));
        }
    }

    @Command(name = "status", description = "Show flows, items and what is waiting for you.")
    static final class Status implements Callable<Integer> {
        @CommandLine.ParentCommand
        GtaCli parent;

        @Override
        public Integer call() {
            return print(parent, engine(parent).status());
        }
    }

    @Command(
            name = "abort",
            description = "Discard working state (human only). Specs, shards and committed tests are kept.")
    static final class Abort implements Callable<Integer> {
        @CommandLine.ParentCommand
        GtaCli parent;

        @Option(names = "--flow")
        String flow;

        @Override
        public Integer call() {
            return print(parent, engine(parent).abort(flow));
        }
    }

    // ------------------------------------------------------------------ verification

    @Command(name = "run", description = "Run gta tests: --smoke (seed tests) or all gta-tagged tests.")
    static final class Run implements Callable<Integer> {
        @CommandLine.ParentCommand
        GtaCli parent;

        @Option(names = "--smoke")
        boolean smoke;

        @Option(
                names = "--tags",
                description = "JUnit tag expression over the gta tags, e.g. \"p0 & component\" or \"hardening\"")
        String tags;

        @Option(names = "--layer", description = "One layer; the same as --tags <layer>")
        String layer;

        @Override
        public Integer call() {
            if (tags != null && layer != null) {
                throw new GtaException(
                        ErrorCode.USAGE_INVALID_ARGUMENT,
                        "--tags and --layer select the same thing",
                        "Use --tags \"" + layer + " & <more>\"");
            }
            RunResult r = engine(parent).runTests(smoke, tags != null ? tags : layer);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("runId", r.runId);
            out.put("tests", r.cases.size());
            out.put("failed", r.failures().size());
            out.put("compileError", r.compileError);
            out.put("log", r.logFile);
            print(parent, out);
            return r.compileError || !r.failures().isEmpty() ? ErrorCode.TEST_ASSERTION_FAILED.exitCode() : 0;
        }
    }

    @Command(
            name = "verify",
            description = "Stability (pass^k, random order, another timezone and locale) for new tests.")
    static final class Verify implements Callable<Integer> {
        @CommandLine.ParentCommand
        GtaCli parent;

        @Option(names = "--new", description = "Only tests that passed in the current session")
        boolean onlyNew;

        @Option(names = "--item", description = "Only this passed item")
        String item;

        @Override
        public Integer call() {
            Map<String, Object> out = engine(parent).verify(onlyNew, item);
            print(parent, out);
            return ((List<?>) out.get("unstable")).isEmpty() ? 0 : ErrorCode.GATE_UNSTABLE_TEST.exitCode();
        }
    }

    @Command(name = "mutate", description = "Mutation testing on changed code (P2).")
    static final class Mutate implements Callable<Integer> {
        @CommandLine.ParentCommand
        GtaCli parent;

        @Option(names = "--changed", required = true, description = "Mutate the production lines changed since --base")
        boolean changed;

        @Option(names = "--base", description = "Base ref (default origin/main, main, ...)")
        String base;

        @Override
        public Integer call() {
            // advisory until calibrated on pilot repositories (plan §15 P2): it reports, it never fails the command
            return print(parent, engine(parent).mutateChanged(base));
        }
    }

    @Command(
            name = "gate",
            description = "Quality gate anchored on the base branch (no LLM).",
            subcommands = GtaCli.Waive.class)
    static final class GateCmd implements Callable<Integer> {
        @CommandLine.ParentCommand
        GtaCli parent;

        @Option(names = "--base")
        String base;

        @Option(
                names = "--reports",
                description = "Surefire/Failsafe report directories from the CI build (repeatable)")
        List<Path> reports = new ArrayList<>();

        @Override
        public Integer call() {
            Gate.Result r = engine(parent).gate(base, reports);
            print(parent, r);
            return "fail".equals(r.decision) ? ErrorCode.GATE_SCENARIO_UNVERIFIED.exitCode() : 0;
        }
    }

    @Command(
            name = "waive",
            description = "Record a gate waiver in .gta/waivers.json (human only; merge it through PR review).")
    static final class Waive implements Callable<Integer> {
        @CommandLine.ParentCommand
        GateCmd gate;

        @Parameters(index = "0", description = "condition code, e.g. GATE_SCENARIO_REGRESSED")
        String code;

        @Option(names = "--target", required = true, description = "scenario id or *")
        String target;

        @Option(names = "--reason", required = true)
        String reason;

        @Option(names = "--expires", required = true, description = "yyyy-mm-dd, at most 90 days ahead")
        String expires;

        @Override
        public Integer call() throws IOException {
            LocalDate exp;
            try {
                exp = LocalDate.parse(expires);
            } catch (DateTimeParseException e) {
                throw new GtaException(
                        ErrorCode.USAGE_INVALID_ARGUMENT,
                        "--expires must be a date like 2026-12-31, got " + expires,
                        "Use yyyy-mm-dd, at most 90 days ahead");
            }
            if (exp.isAfter(LocalDate.now().plusDays(90))) {
                throw new GtaException(
                        ErrorCode.USAGE_INVALID_ARGUMENT,
                        "Waivers may last at most 90 days",
                        "Pick an earlier --expires");
            }
            Path file = new GtaPaths(gate.parent.root).waivers();
            List<Map<String, Object>> list = new ArrayList<>();
            if (Files.exists(file)) {
                list.addAll(Json.MAPPER.readValue(file.toFile(), new TypeReference<List<Map<String, Object>>>() {}));
            }
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("code", code);
            w.put("target", target);
            w.put("reason", reason);
            w.put("expires", expires);
            w.put("by", gitUser(gate.parent.root));
            list.add(w);
            Json.writeAtomic(file, list);
            return print(
                    gate.parent,
                    Map.of(
                            "result",
                            "waiver recorded in .gta/waivers.json; it applies after it is merged to the base branch"));
        }
    }

    @Command(name = "relock", description = "Re-lock an item's oracle after an intended test change (human only).")
    static final class Relock implements Callable<Integer> {
        @CommandLine.ParentCommand
        GtaCli parent;

        @Option(names = "--item", required = true)
        String item;

        @Option(names = "--reason", required = true)
        String reason;

        @Override
        public Integer call() {
            return print(parent, engine(parent).relock(item, reason, gitUser(parent.root)));
        }
    }

    @Command(name = "report", description = "Traceability report (md) or PR comment (pr-comment).")
    static final class Report implements Callable<Integer> {
        @CommandLine.ParentCommand
        GtaCli parent;

        @Option(names = "--kind", description = "md | pr-comment")
        String kind = "md";

        @Override
        public Integer call() {
            System.out.println(engine(parent).report(kind));
            return 0;
        }
    }

    @Command(
            name = "cases",
            description =
                    "Test case ledgers (.gta/specs/<domain>/<flow>.cases.md): overview, or --rebuild from approved specs.",
            subcommands = GtaCli.ConfirmRetired.class)
    static final class Cases implements Callable<Integer> {
        @CommandLine.ParentCommand
        GtaCli parent;

        @Option(names = "--rebuild", description = "Create or refresh the ledgers of every approved spec")
        boolean rebuild;

        @Override
        public Integer call() {
            return print(parent, engine(parent).cases(rebuild));
        }
    }

    @Command(
            name = "confirm-retired",
            description = "Delete the tests of retired cases (human only; nothing is ever deleted automatically).")
    static final class ConfirmRetired implements Callable<Integer> {
        @CommandLine.ParentCommand
        Cases cases;

        @CommandLine.Parameters(description = "Case ids, e.g. order.create/s03")
        List<String> ids = new ArrayList<>();

        @Option(names = "--all", description = "Every retired case")
        boolean all;

        @Override
        public Integer call() {
            if (!all && ids.isEmpty()) {
                throw new GtaException(
                        ErrorCode.USAGE_INVALID_ARGUMENT,
                        "Name the retired cases to delete, or pass --all",
                        "The run report lists them; ./gtaw cases shows them too");
            }
            return print(cases.parent, engine(cases.parent).confirmRetired(ids, all));
        }
    }

    @Command(
            name = "impact",
            description = "Cases whose code changed since --base need review; prints the regression scope (+ all P0).")
    static final class Impact implements Callable<Integer> {
        @CommandLine.ParentCommand
        GtaCli parent;

        @Option(names = "--base", description = "Base ref (default origin/main, main, ...)")
        String base;

        @Override
        public Integer call() {
            return print(parent, engine(parent).impact(base));
        }
    }

    @Command(name = "env", description = "Ephemeral cloud environments (P4).")
    static final class Env implements Callable<Integer> {
        @Override
        public Integer call() {
            throw new GtaException(
                    ErrorCode.USAGE_NOT_AVAILABLE_IN_PHASE,
                    "Ephemeral environments arrive in P4",
                    "Not needed for component tests");
        }
    }

    static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new GtaException(ErrorCode.USAGE_INVALID_ARGUMENT, name + " is required", "Copy it from the brief");
        }
        return value;
    }

    static String gitUser(Path root) {
        ProcessRunner.Result r = ProcessRunner.run(root, ProcessRunner.cmd("git", "config", "user.email"));
        return r.ok() && !r.output().isBlank() ? r.output().trim() : System.getProperty("user.name", "unknown");
    }
}
