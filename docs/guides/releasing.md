# Releasing gta

Who: the platform team. A release is a version that services can pin: the wrapper downloads one jar by URL and
verifies its sha256, so a version, once published, never changes.

The steps below were rehearsed against a local file repository (`mvn deploy` at `0.1.0`, then `init` with that jar's
URL and sha256). Only the Nexus upload itself needs your credentials.

## 1. Prepare (a normal PR)

1. Set the release version in every pom and in `evals/e2e/run.sh` (they all say `<version>-SNAPSHOT` before a release):
   ```bash
   grep -rl "0.1.0-SNAPSHOT" pom.xml toolkit/*/pom.xml evals/e2e/run.sh | xargs sed -i 's/0\.1\.0-SNAPSHOT/0.1.0/g'
   ```
2. In `CHANGELOG.md`, rename `[Unreleased]` to `[<version>] — <yyyy-MM-dd>` and add an empty `[Unreleased]` above it.
3. Run the checks from AGENTS.md ("Before you push"). CI must be green on the PR.

## 2. Publish

From the merged commit on `main`:

```bash
mvn -B deploy -DskipTests -DaltDeploymentRepository=nexus::https://nexus.example.com/repository/maven-releases
```

(`-DskipTests` only because CI already ran them on this commit.) Five artifacts are uploaded under `com.gta`:
`gta-cli` (the shaded `gta-cli-<version>.jar` that services run), `gta-junit` (services depend on it in test scope),
`gta-core`, `gta-harness-gen` and `gta-parent` (their poms and jars; the shaded jar already contains core).

Then compute the checksum services must pin and test it the way a service would:

```bash
sha256sum gta-cli/target/gta-cli.jar        # or the file downloaded back from Nexus
java -jar gta-cli-<version>.jar --version   # prints the version
```

Download the jar back from Nexus and compare its sha256: the pinned value must be that of the file Nexus serves.

## 3. Tag and announce

```bash
git tag -a v<version> -m "gta <version>" && git push origin v<version>
```

Create a GitHub Release from the tag: paste the changelog section, attach `gta-cli.jar` and `gta-junit.jar` with
their sha256 values. Publish to services the line they need:

```bash
java -jar gta-cli-<version>.jar --root path/to/service init --platform copilot \
  --distribution-url https://nexus.example.com/repository/maven-releases/com/gta/gta-cli/<version>/gta-cli-<version>.jar \
  --distribution-sha256 <sha256>
```

Existing services move with `gtaw upgrade` after changing the pinned URL and sha256 in
`.gta/wrapper/gta-wrapper.properties` (the changelog says what changed; rollback is pinning the previous version).

## 4. Reopen development

Set the next `-SNAPSHOT` version in the same places as step 1 (for example `0.1.1-SNAPSHOT`) and commit.

## First release, `0.1.0`

State plainly in the changelog and the GitHub Release: **P0 preview, REST APIs only**; other capabilities are
explain-only (see the capability registry). Findings from the P0a validation go into `0.1.1`.
