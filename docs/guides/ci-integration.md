# CI integration (optional)

The gate already runs locally at the end of every `/gta` session. Adding it to CI (alm-github / GitHub Actions) makes it hold for every PR, including PRs written without gta.

The gate is deterministic. It uses no LLM, needs no credentials beyond Nexus, and is anchored on the **base branch**:

| | Failure condition |
|---|---|
| ① | A scenario whose test passed on the base must still pass. |
| ② | A scenario that is new or changed in this PR needs a verified test (its oracle shard entry). |
| ③ | A locked expectation may not change unless the spec changed too, or a human relocked it. |
| ④ | New tests must be stable under repeated runs, random order, the Asia/Kolkata timezone and the de-DE locale (taken from the session's verify record when present). |

The gate has three outcomes:

- `fail`: exit code ≠ 0.
- `concerns`: passes, with notes. For example: unconfirmed `assumed`/`defaulted` claims, new human confirmations, or a high share of characterization tests in the changed flows.
- `pass`.

Waivers live in `.gta/waivers.json` on the base branch, so adding one needs its own reviewed PR. To create one:

```bash
./gtaw gate waive <GATE_CODE> --target <scenarioId|*> --reason "..." --expires <yyyy-mm-dd>
```

## Workflow for a service repository

`./gtaw init --platform copilot --ci` (or `opencode`) installs it as `.github/workflows/gta-gate.yml`, a managed file:
`gtaw upgrade` keeps it current and reports local edits instead of overwriting them. In a multi-module repository the
`--reports` folders are those of `build.module`, and `./mvnw` is used when the repository has the wrapper. The installed
workflow looks like this:

```yaml
name: gta gate
on:
  pull_request:
    paths-ignore: ["**/*.md", "!.gta/specs/**"]

permissions:
  contents: read
  pull-requests: write

jobs:
  gate:
    runs-on: [self-hosted, linux]          # a runner that can reach Nexus
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }           # the gate reads the base branch's specs and shards
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21", cache: maven }
      - name: Build and run tests
        run: mvn -B -s .mvn/nexus-settings.xml verify
      - name: gta gate
        env:
          GTA_NEXUS_USER: ${{ secrets.NEXUS_USER }}          # used by gtaw to download the pinned gta-cli jar
          GTA_NEXUS_PASSWORD: ${{ secrets.NEXUS_PASSWORD }}
        run: |
          ./gtaw gate --base "origin/${{ github.base_ref }}" \
            --reports target/surefire-reports --reports target/failsafe-reports > gate.json || echo "GATE_FAILED=1" >> "$GITHUB_ENV"
          ./gtaw report --kind pr-comment > pr-comment.md
          cat gate.json
      - name: Comment on the PR
        uses: marocchino/sticky-pull-request-comment@v2
        with: { path: pr-comment.md, header: gta }
      - name: Fail when the gate failed
        if: env.GATE_FAILED == '1'
        run: exit 1
```

Notes:

- With `--reports`, the gate reads the reports of the build that just ran instead of running Maven again. Without it, the gate runs every test tagged `gta` itself, including `*IT` classes, through Surefire.
- The JUnit listener in `gta-junit` writes `target/gta-results/*.jsonl`, which maps each test to its scenario. Keep `gta-junit` on the test classpath.
- Replace the sticky-comment action with your organisation's approved equivalent if third-party actions are restricted.
- `CODEOWNERS` for `.gta/**` and branch protection are optional. When they are enabled, spec approvals and waivers also need a code owner's review.

## This repository's own CI

`.github/workflows/ci.yml` builds the toolkit and runs its unit tests. The tests also fail when `copilot/` or `opencode/` are stale. The workflow then runs the scripted end-to-end check `evals/e2e/run.sh`.
