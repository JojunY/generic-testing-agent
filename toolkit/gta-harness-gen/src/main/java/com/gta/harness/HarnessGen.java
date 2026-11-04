package com.gta.harness;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maintainer tool for this repository (not shipped to services).
 *
 * <pre>
 * java -jar gta-harness-gen.jar lint     [--repo DIR]
 * java -jar gta-harness-gen.jar generate [--repo DIR] [--check] [--platform copilot|opencode]
 * </pre>
 *
 * {@code generate} lints first, then renders copilot/ and opencode/ from core/ and platforms/. With {@code --check} it
 * writes nothing and exits 1 when the committed folders are out of date (used by CI).
 */
public final class HarnessGen {
    static final List<String> PLATFORMS = List.of("copilot", "opencode");

    private HarnessGen() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            System.out.println("usage: gta-harness-gen lint|generate [--repo DIR] [--check] [--platform copilot|opencode]");
            return args.length == 0 ? 2 : 0;
        }
        String command = args[0];
        Path repo = Path.of(".");
        boolean check = false;
        List<String> platforms = new ArrayList<>(PLATFORMS);
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--repo" -> repo = Path.of(args[++i]);
                case "--check" -> check = true;
                case "--platform" -> platforms = List.of(args[++i]);
                default -> {
                    System.err.println("unknown option " + args[i]);
                    return 2;
                }
            }
        }
        repo = repo.toAbsolutePath().normalize();

        Linter linter = new Linter(repo);
        boolean ok = linter.run();
        linter.warnings().forEach(w -> System.out.println("warning: " + w));
        linter.errors().forEach(e -> System.out.println("error:   " + e));
        if (!ok) {
            System.out.println("lint failed: " + linter.errors().size() + " error(s)");
            return 1;
        }
        if (command.equals("lint")) {
            System.out.println("lint ok (" + linter.warnings().size() + " warning(s))");
            return 0;
        }
        if (!command.equals("generate")) {
            System.err.println("unknown command " + command);
            return 2;
        }

        Generator gen = new Generator(repo);
        int stale = 0;
        for (String p : platforms) {
            if (!PLATFORMS.contains(p)) {
                System.err.println("unknown platform " + p);
                return 2;
            }
            Map<String, String> files = gen.render(p);
            Path target = repo.resolve(p);
            if (check) {
                List<String> diff = gen.diff(target, files);
                diff.forEach(d -> System.out.println(d));
                stale += diff.size();
            } else {
                gen.write(p, target, files);
                System.out.println("generated " + p + "/ (" + files.size() + " files)");
            }
        }
        if (check) {
            if (stale > 0) {
                System.out.println(stale + " file(s) out of date; run scripts/generate.sh and commit the result");
                return 1;
            }
            System.out.println("copilot/ and opencode/ are in sync with core/");
        }
        return 0;
    }
}
