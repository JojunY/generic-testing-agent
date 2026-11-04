package com.gta.harness;

import com.gta.core.registry.Registry;
import com.gta.core.util.Ids;
import com.gta.core.util.Json;
import com.gta.core.util.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Lints core/ harness assets against the naming and structure rules of plan §9.2 and §11.5. */
public class Linter {
    static final Set<String> PROFILES = Set.of("orchestrator", "spec-writer", "test-writer", "read-only");
    static final Set<String> TIERS = Set.of("reasoning", "standard");
    static final List<String> AGENT_SECTIONS = List.of("Role", "Inputs", "Workflow", "Output contract", "Rules");
    static final List<String> SKILL_SECTIONS = List.of("Quick start", "Workflow", "Rules", "References");
    static final Set<String> AGENT_FIELDS = Set.of("name", "description", "permissionProfile", "modelTier", "userInvocable", "subagents");
    static final Pattern REF_LINK = Pattern.compile("references/([A-Za-z0-9._-]+(?:/[A-Za-z0-9._/-]+)?)");
    static final int MAX_AGENT_CHARS = 30_000;
    static final int MAX_SKILL_LINES = 500;
    static final int MAX_DESCRIPTION = 1024;

    private final Path repo;
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public Linter(Path repo) {
        this.repo = repo;
    }

    public List<String> errors() {
        return errors;
    }

    public List<String> warnings() {
        return warnings;
    }

    public boolean run() {
        Set<String> agents = lintAgents();
        Set<String> skills = lintSkills();
        lintCommand(agents);
        lintRegistry(skills);
        lintTemplatesAndPlatforms();
        return errors.isEmpty();
    }

    private Set<String> lintAgents() {
        Set<String> names = new HashSet<>();
        List<Frontmatter> parsed = new ArrayList<>();
        for (Path p : Generator.list(repo.resolve("core/agents"), ".md")) {
            String where = rel(p);
            String text = Generator.read(p);
            Frontmatter fm;
            try {
                fm = Frontmatter.parse(text);
            } catch (RuntimeException e) {
                errors.add(where + ": " + e.getMessage());
                continue;
            }
            Map<String, Object> f = fm.fields();
            String name = String.valueOf(f.get("name"));
            names.add(name);
            parsed.add(fm);
            if (!Ids.AGENT_NAME.matcher(name).matches()) {
                errors.add(where + ": agent name '" + name + "' must match " + Ids.AGENT_NAME.pattern());
            }
            if (!p.getFileName().toString().equals(name + ".md")) {
                errors.add(where + ": file name must be " + name + ".md");
            }
            for (String k : f.keySet()) {
                if (!AGENT_FIELDS.contains(k)) {
                    errors.add(where + ": unknown frontmatter field '" + k + "' (neutral fields only: " + AGENT_FIELDS + ")");
                }
            }
            description(where, f.get("description"));
            if (!PROFILES.contains(String.valueOf(f.get("permissionProfile")))) {
                errors.add(where + ": permissionProfile must be one of " + PROFILES);
            }
            if (!TIERS.contains(String.valueOf(f.get("modelTier")))) {
                errors.add(where + ": modelTier must be one of " + TIERS);
            }
            if (!(f.get("userInvocable") instanceof Boolean)) {
                errors.add(where + ": userInvocable must be true or false");
            }
            if (text.length() > MAX_AGENT_CHARS) {
                errors.add(where + ": " + text.length() + " characters, limit " + MAX_AGENT_CHARS);
            }
            sections(where, fm.body(), AGENT_SECTIONS);
        }
        for (Frontmatter fm : parsed) {
            if (fm.fields().get("subagents") instanceof List<?> subs) {
                for (Object s : subs) {
                    if (!names.contains(String.valueOf(s))) {
                        errors.add("core/agents/" + fm.fields().get("name") + ".md: subagent '" + s + "' does not exist");
                    }
                }
            }
        }
        long invocable = parsed.stream().filter(f -> Boolean.TRUE.equals(f.fields().get("userInvocable"))).count();
        if (invocable != 1) {
            errors.add("core/agents: exactly one agent must be userInvocable (the orchestrator), found " + invocable);
        }
        return names;
    }

    private Set<String> lintSkills() {
        Set<String> names = new HashSet<>();
        Path root = repo.resolve("core/skills");
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                String dirName = dir.getFileName().toString();
                names.add(dirName);
                Path skill = dir.resolve("SKILL.md");
                String where = rel(skill);
                if (!Ids.SKILL_NAME.matcher(dirName).matches() || dirName.length() > 64) {
                    errors.add(rel(dir) + ": skill directory must match " + Ids.SKILL_NAME.pattern() + " and be at most 64 characters");
                }
                if (!Files.exists(skill)) {
                    errors.add(rel(dir) + ": missing SKILL.md");
                    continue;
                }
                String text = Generator.read(skill);
                Frontmatter fm;
                try {
                    fm = Frontmatter.parse(text);
                } catch (RuntimeException e) {
                    errors.add(where + ": " + e.getMessage());
                    continue;
                }
                if (!dirName.equals(fm.fields().get("name"))) {
                    errors.add(where + ": name must equal the directory name '" + dirName + "'");
                }
                for (String k : fm.fields().keySet()) {
                    if (!Set.of("name", "description").contains(k)) {
                        errors.add(where + ": skills only have name and description in frontmatter, found '" + k + "'");
                    }
                }
                description(where, fm.fields().get("description"));
                long lines = text.lines().count();
                if (lines > MAX_SKILL_LINES) {
                    errors.add(where + ": " + lines + " lines, limit " + MAX_SKILL_LINES + " (move detail to references/)");
                }
                sections(where, fm.body(), SKILL_SECTIONS);
                references(dir, fm.body());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return names;
    }

    private void references(Path dir, String body) {
        Path refs = dir.resolve("references");
        Set<String> linked = new HashSet<>();
        Matcher m = REF_LINK.matcher(body);
        while (m.find()) {
            String target = m.group(1).replaceAll("[.,;:)`]+$", "");
            linked.add(target);
            if (target.contains("/")) {
                errors.add(rel(dir.resolve("SKILL.md")) + ": reference '" + target + "' is nested; keep references one level deep");
            } else if (!Files.exists(refs.resolve(target))) {
                errors.add(rel(dir.resolve("SKILL.md")) + ": linked reference references/" + target + " does not exist");
            }
        }
        if (Files.isDirectory(refs)) {
            try (Stream<Path> s = Files.list(refs)) {
                for (Path p : s.sorted().toList()) {
                    if (Files.isDirectory(p)) {
                        errors.add(rel(p) + ": no subdirectories under references/");
                    } else if (!linked.contains(p.getFileName().toString())) {
                        warnings.add(rel(p) + ": not linked from SKILL.md");
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void lintCommand(Set<String> agents) {
        Path cmd = repo.resolve("core/commands/gta.md");
        if (!Files.exists(cmd)) {
            errors.add("core/commands/gta.md: missing (gta has exactly one command)");
            return;
        }
        Frontmatter fm = Frontmatter.parse(Generator.read(cmd));
        if (!"gta".equals(fm.fields().get("name"))) {
            errors.add("core/commands/gta.md: name must be gta");
        }
        if (!agents.contains(String.valueOf(fm.fields().get("agent")))) {
            errors.add("core/commands/gta.md: agent '" + fm.fields().get("agent") + "' does not exist");
        }
        if (!fm.body().contains("{{args}}")) {
            warnings.add("core/commands/gta.md: body does not use {{args}}");
        }
        for (Path p : Generator.list(repo.resolve("core/commands"), ".md")) {
            if (!p.equals(cmd)) {
                errors.add(rel(p) + ": only the gta command is allowed");
            }
        }
    }

    private void lintRegistry(Set<String> skills) {
        Path file = repo.resolve("core/registry/capabilities.yaml");
        Registry registry;
        try {
            registry = Yaml.MAPPER.readValue(file.toFile(), Registry.class);
        } catch (IOException e) {
            errors.add(rel(file) + ": " + e.getMessage());
            return;
        }
        Set<String> ids = new HashSet<>();
        for (Registry.Capability c : registry.capabilities) {
            if (!ids.add(c.id)) {
                errors.add(rel(file) + ": duplicate capability " + c.id);
            }
            if (!c.id.matches("^[a-z]+(-[a-z]+)*$")) {
                errors.add(rel(file) + ": capability id '" + c.id + "' must be kebab-case");
            }
            if (!c.since.matches("^P[0-9]+$")) {
                errors.add(rel(file) + ": capability " + c.id + " has invalid since '" + c.since + "'");
            }
            for (String s : c.skills) {
                if (!skills.contains(s)) {
                    String msg = rel(file) + ": capability " + c.id + " references missing skill " + s;
                    if (c.activeIn(Registry.CURRENT_PHASE)) {
                        errors.add(msg);
                    } else {
                        warnings.add(msg + " (capability not active in " + Registry.CURRENT_PHASE + ")");
                    }
                }
            }
        }
        for (String s : registry.always.skills) {
            if (!skills.contains(s)) {
                errors.add(rel(file) + ": always-skill " + s + " does not exist");
            }
        }
    }

    private void lintTemplatesAndPlatforms() {
        for (String p : List.of("copilot", "opencode")) {
            if (!Files.exists(repo.resolve("platforms/" + p + "/mapping.yaml"))) {
                errors.add("platforms/" + p + "/mapping.yaml: missing");
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> profiles = (Map<String, Object>) Generator.yaml(repo.resolve("platforms/" + p + "/mapping.yaml")).get("profiles");
            for (String profile : PROFILES) {
                if (profiles == null || !profiles.containsKey(profile)) {
                    errors.add("platforms/" + p + "/mapping.yaml: no mapping for permission profile " + profile);
                }
            }
        }
        Path schemas = repo.resolve("core/schemas");
        if (Files.isDirectory(schemas)) {
            for (Path p : Generator.list(schemas, ".json")) {
                Matcher m = Pattern.compile("^([a-z0-9-]+)\\.v(\\d+)\\.schema\\.json$").matcher(p.getFileName().toString());
                if (!m.matches()) {
                    errors.add(rel(p) + ": schema names follow <artifact>.v<major>.schema.json");
                    continue;
                }
                try {
                    Object id = Json.MAPPER.readValue(p.toFile(), Map.class).get("$id");
                    String expected = "urn:gta:schema:" + m.group(1) + ":v" + m.group(2);
                    if (!expected.equals(id)) {
                        errors.add(rel(p) + ": $id must be " + expected);
                    }
                } catch (IOException e) {
                    errors.add(rel(p) + ": invalid JSON: " + e.getMessage());
                }
            }
        }
        try (Stream<Path> s = Files.list(repo.resolve("core/templates"))) {
            for (Path t : s.toList()) {
                if (!t.getFileName().toString().matches("^[a-z0-9-]+\\.[a-z]+\\.tmpl$")) {
                    errors.add(rel(t) + ": template names follow <artifact>.<ext>.tmpl");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void description(String where, Object d) {
        String s = d == null ? "" : d.toString();
        if (s.isBlank()) {
            errors.add(where + ": description is required");
            return;
        }
        if (s.length() > MAX_DESCRIPTION) {
            errors.add(where + ": description is " + s.length() + " characters, limit " + MAX_DESCRIPTION);
        }
        if (!s.contains("Use when") && !s.contains("Use only when")) {
            errors.add(where + ": description must say what it does and 'Use when ...'");
        }
        if (s.matches("^(I|You|We|Use this)\\b.*")) {
            errors.add(where + ": description must be written in the third person");
        }
    }

    private void sections(String where, String body, List<String> expected) {
        List<String> found = body.lines().filter(l -> l.startsWith("## ")).map(l -> l.substring(3).replaceAll("\\s*\\(.*\\)\\s*$", "").strip()).toList();
        int last = -1;
        for (String s : expected) {
            int i = found.indexOf(s);
            if (i < 0) {
                errors.add(where + ": missing section '## " + s + "'");
            } else if (i < last) {
                errors.add(where + ": section '## " + s + "' is out of order (expected " + expected + ")");
            } else {
                last = i;
            }
        }
    }

    private String rel(Path p) {
        return repo.relativize(p).toString().replace('\\', '/');
    }
}
