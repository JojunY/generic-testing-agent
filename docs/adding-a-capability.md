# Adding a capability

A capability is a kind of flow gta can test, such as `rest-api`, `scheduler` or `messaging`. Adding one never needs a new agent. Agents are roles; capabilities are knowledge (skills) plus detection and planning data.

1. **Registry**: add an entry to `core/registry/capabilities.yaml`.

   ```yaml
   - id: file-import              # kebab-case noun
     since: P2                    # explain-only before this phase
     detect:
       buildDeps: [spring-integration-file]
       annotations: ["@InboundChannelAdapter"]
       files: ["**/import/*.csv"]
     layers: [unit, component]    # the first of component/unit is the default layer
     diagrams: [flowchart]
     skills: [gta-testing-file-imports, gta-using-embedded-dependencies]
   ```

   Use `dispatch: false` for a capability that never produces flows of its own and only adds skills to detected items, like `data-dependencies`.

2. **Skill**: create `core/skills/gta-<gerund>-<object>/SKILL.md`.
   - Frontmatter: only `name` (equal to the directory name) and a third-person `description` that says what the skill does and "Use when …".
   - Body sections, in order: `Quick start`, `Workflow`, `Rules`, `References`.
   - At most 500 lines. Move detail to `references/*.md`, one level deep, each linked from SKILL.md.
   - Name the expected-value rules explicitly: what the test must assert and what fidelity level it has.

3. **Entry points**: if flows of this capability are not HTTP endpoints, `@Scheduled` methods or listeners, teach `Profiler.entrypoints` (gta-core) how to find them. Each entry point becomes a flow id `<domain>.<flow-name>`.

4. **Generate and lint**:

   ```bash
   scripts/generate.sh             # lints core/ and regenerates copilot/ and opencode/
   mvn -B install                # unit tests include the lint and the sync check
   ```

5. **Eval**: add a fixture flow under `evals/fixtures/` with at least one seeded defect for the new capability. Extend `evals/e2e/` so a correct agent output catches it. Activate the capability (`since`) only after the eval passes.
