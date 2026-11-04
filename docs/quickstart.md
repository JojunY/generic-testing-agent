# Quick start

Who does what:

- **Tech lead, once per service:** steps 1–3.
- **Every developer:** step 4.

## 1. Publish gta to Nexus (platform team, once per release)

```bash
mvn -B deploy -DaltDeploymentRepository=nexus::https://nexus.example.com/repository/maven-releases
```

This publishes two artifacts:

- `com.gta:gta-cli:<version>` (the shaded `gta-cli.jar`)
- `com.gta:gta-junit:<version>` (a test-scope dependency of services)

All downloads, for the wrapper and for Maven, go through the company Nexus proxy.

Optional: upload the Pub/Sub emulator archive to a Nexus raw repository and set `distribution.toolsUrl` (see `.gta/skills/gta-using-embedded-dependencies/references/pubsub-emulator.md`). Without it, Pub/Sub flows use in-memory fakes.

## 2. Install the harness into a service

Pick the folder your team uses: `copilot` (VS Code or IntelliJ Copilot) or `opencode`.

```bash
java -jar gta-cli-<version>.jar --root path/to/service init --platform copilot \
  --distribution-url https://nexus.example.com/repository/maven-releases/com/gta/gta-cli/<version>/gta-cli-<version>.jar \
  --distribution-sha256 <sha256 of that jar>
```

`init` writes:

| File | Purpose |
|---|---|
| `.github/agents/gta-*.agent.md`, `.github/prompts/gta.prompt.md` | Copilot agents and the `/gta` command (OpenCode: `.opencode/agents`, `.opencode/commands`) |
| `.gta/skills/**` | testing skills, loaded by path from briefs |
| `AGENTS.md` | a gta block between `<!-- gta:begin -->` and `<!-- gta:end -->`; the rest of the file is kept |
| `.vscode/settings.json`, `.vscode/extensions.json` / `opencode.json` | merged, never overwritten: auto-approve for `./gtaw next|check|submit|answer|status`, the Mermaid preview. A file with comments is left untouched and `init` prints the keys to add (`mergeManually`) |
| `gtaw`, `gtaw.cmd`, `.gta/wrapper/*` | the wrapper: it downloads the pinned jar from Nexus, verifies its sha256 and runs it |
| `.gitignore` | adds `.gta/work/` (rebuildable working state) |

Add the test dependency. If you skip this, the first foundation item adds it.

```xml
<dependency>
  <groupId>com.gta</groupId>
  <artifactId>gta-junit</artifactId>
  <version><!-- gta version --></version>
  <scope>test</scope>
</dependency>
```

Commit everything.

## 3. Check the machine

```bash
./gtaw doctor        # Windows: .\gtaw.cmd doctor
```

`doctor` checks:

- the JDK, Maven (or `mvnw`), git and the Spring Boot/JUnit versions;
- gta-junit and the harness files;
- the Oracle test schema and the emulators;
- IDE auto-approve.

Warnings are fine to start with. On IntelliJ, add the auto-approve rules by hand: Settings > GitHub Copilot > Chat > Auto Approve. Add `./gtaw` and `.\gtaw.cmd` with the subcommands `next`, `check`, `submit`, `answer` and `status`.

## 4. Use it

Open Copilot Chat (agent mode) or OpenCode in the service and type:

| You type | What happens |
|---|---|
| `/gta` | continue, or list the riskiest flows to pick from |
| `/gta explain order.create` | write the behaviour spec and questions only, no tests |
| `/gta order.create` | spec → questions → approval → tests for that flow |
| `/gta status` | flows, items and what is waiting for you |

gta stops and waits for you at three points.

**Questions.** Reply with the round code it shows:

```
R1: 1B 2A 其余默认
```

High-impact questions need an explicit answer. `其余默认` (or `rest default`) picks the "= 代码现状" (current code) option for the rest. Those scenarios become *characterization* tests: they lock today's behaviour and do not prove it is correct.

**Approval.** Reply with the revision it shows:

```
批准 order.create@R2
```

`approve order.create@R2` also works. Approval is committed with your words as the record.

**Defects and escalations.** Reply with a letter (A, B, C). A confirmed defect is pinned with `@KnownDefect`:

- the build stays green while the defect exists;
- the test turns red once the defect is fixed, which tells you to remove the pin.

If your terminal mangles Chinese characters, gta says so. Use the ASCII forms above, or put the reply in a UTF-8 file and run `./gtaw answer --file reply.txt`.

## Configuration (optional)

`.gta/config.json` overrides the defaults. Only the keys you set are used.

```json
{
  "build": { "basePackage": "auto", "maven": "auto" },
  "dispatch": "subagent",
  "environments": { "oracle": { "testUrl": "jdbc:oracle:thin:@//test-db:1521/TESTPDB", "userEnv": "GTA_ORACLE_USER", "passwordEnv": "GTA_ORACLE_PASSWORD" } },
  "distribution": { "toolsUrl": "https://nexus.example.com/repository/raw-tools/gta" },
  "budgets": { "maxItemsPerSession": 5, "maxAttemptsPerItem": 2, "nodeTimeoutMinutes": 15 },
  "qualityGates": { "stabilityRepeat": 3 }
}
```

- `dispatch: "inline"`: use this if your Copilot build cannot run subagents; the orchestrator then does each node itself.
- Oracle credentials are read from the named environment variables and never stored.

## When something goes wrong

| Situation | Do this |
|---|---|
| Chat closed or crashed mid-run | `/gta` again. State lives in `.gta/work/`, and a session idle for 10 minutes can be taken over with `./gtaw next --takeover`. |
| Want to start over | `./gtaw abort` (or `--flow <flowId>`). Specs, oracle shards and committed tests are kept. |
| An item needs you | `./gtaw status --format human`; details are in `.gta/escalations/<item>.md`. |
| A test changed on purpose | `./gtaw relock --item <itemId> --reason "..."` (human only; the gate shows it). |
