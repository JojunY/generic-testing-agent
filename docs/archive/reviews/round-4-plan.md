# Review round 4: what to fix, and what not to

An outside review raised four points. Each one is checked against the code here (main at the time of writing), so
that we fix the real problems and skip the ones that would only add work.

| # | Claim | Verdict | Do |
|---|---|---|---|
| 1 | No release: 0 tags, everything is `Unreleased` | **Real** | R1 |
| 2 | One fixture only; no agent-side regression | **Partly real** | R4 (later); no new fixtures now |
| 3 | gta-cli has the thinnest tests, and the installer writes into other teams' repositories | **Partly real**: the numbers are wrong, but there is a real data-loss bug | R2, R3 |
| 4 | Windows skips the coverage gate; line coverage does not show assertion strength | **Windows part: not a problem. Mutation part: worth one advisory run** | R5 |

## Findings

### 1. No release (real)

- `git tag` is empty, the version is `0.1.0-SNAPSHOT`, the changelog has only `[Unreleased]`.
- The wrapper pins a jar by version and sha256, so no service can adopt gta until a fixed version exists.
- The publish step in `docs/guides/quickstart.md` §1 has never been run.

This blocks adoption, so it comes first. It does not have to wait for P0a: a `0.1.0` that says "P0 preview, REST
APIs only" is honest, and the P0a validation can produce `0.1.1`.

### 2. Validation breadth (partly real)

- True: `evals/fixtures/` has only `order-service`, and the agent side (prompts, skills) has no automated regression;
  `evals/README.md` describes P0a model evals as a manual scoring run.
- Not a problem now:
  - **Batch, Kafka, Beam and contract fixtures.** These capabilities are explain-only in P0, in the capability
    registry. A fixture would test a skill that gtaw never dispatches. Each phase should ship its fixture when it
    activates the capability; that belongs in that phase's exit criteria, not in this round.
  - **A multi-module "dirty" fixture.** Q-R7 decided single-module only, and `gtaw doctor` now warns about it.
    Building a fixture for an unsupported shape is wasted work.
- Worth doing, but later (R4): a repeatable agent eval on `order-service`, once P0a has run the manual one and we know
  what to score. Automating a scoring sheet we have never used once would freeze the wrong metrics.

### 3. gta-cli tests (partly real)

Measured with JaCoCo on `gta-cli` (its tests only):

| Class | Lines | Branches |
|---|---|---|
| `Installer` | 175/192 (91 %) | 89/109 (82 %) |
| `DoctorChecks` | 73/111 (66 %) | 36/94 (38 %) |
| `GtaCli` (command classes) | low | low |

- The installer is **not** the weakest part: JSONC files, unparseable inputs, idempotent re-runs, locally edited and
  retired managed files already have tests (`InstallerTest`).
- `GtaCli` command classes are thin wrappers around `Engine`; the scripted e2e runs them through the real jar. No
  separate tests needed.
- **Real bug found while checking (R2):** `Installer.mergedAgentsMd` looks for `BEGIN` and `END` markers. If a team's
  `AGENTS.md` has a `BEGIN` marker without its `END` (a hand-edited or half-deleted block), `init` appends a second
  block. The next `upgrade` then matches the *first* `BEGIN` with the *new* `END` and **replaces everything between
  them, deleting the team's own text.** An `END` before `BEGIN` takes the same path. This is exactly the
  "writes into someone else's repository" risk the review worried about.
- **Real gap (R3):** `DoctorChecks` covers 38 % of its branches. Doctor is the first command a team runs, and a wrong
  `ok` hides a broken setup.

### 4. Coverage gate (Windows part: no change; mutation: one advisory run)

- Windows: only `MavenRunnerTest` skips on Windows (2 tests, a POSIX shell stand-in for Maven). The code they cover
  is the same on both platforms and the Linux jobs gate it. Windows-only paths (`gtaw.cmd`, `ProcessRunner` on
  Windows) are run by the Windows job and its `gtaw.cmd` smoke step. A second, Windows-only coverage gate would add
  configuration without catching anything new. **No change.**
- Mutation: true that 90 % lines does not prove assertions are strong, and gta teaches mutation testing in
  `gta-reviewing-test-quality`. But a permanent gate costs build time on every PR, and we do not yet know the score.
  **One advisory PIT run (R5)** on the core decisions tells us whether weak tests exist; only then decide on a gate.

## Plan

Ordered by value. "Size" is a rough effort estimate.

| Id | Change | Size | Done when |
|---|---|---|---|
| R1 | **Release 0.1.0.** Set the version to `0.1.0`; move `[Unreleased]` to `[0.1.0] — <date>` with "P0 preview: REST APIs"; add `docs/guides/releasing.md` (version bump, `mvn deploy` to Nexus, sha256 of `gta-cli.jar`, the `gtaw install` line to publish, tag `v0.1.0`, next `-SNAPSHOT`); a GitHub Release with `gta-cli.jar`, `gta-junit.jar` and their sha256. | half a day | tag `v0.1.0` exists; the platform team has run `releasing.md` once against Nexus and fixed what was wrong; `gtaw install` with that URL and sha works in `order-service` |
| R2 | **Fix the `AGENTS.md` marker bug.** A `BEGIN` without a matching `END` after it (or the reverse) is refused with a hint (`CONFIG_INVALID`, "fix the gta block in AGENTS.md by hand"), and nothing is written. Tests: lone `BEGIN`, lone `END`, `END` before `BEGIN`, two blocks. | 1 hour | `InstallerTest` covers the four shapes; no shape loses team text |
| R3 | **Doctor branch tests.** One test per check result (`ok`, `warn`, `fail`) for the checks that decide something: Java and Maven versions, Failsafe present, git state, Oracle and Pub/Sub only when used, `modules`. | half a day | `DoctorChecks` ≥ 80 % branches; each test asserts the documented status and hint |
| R4 | **Agent eval, after P0a.** Turn the P0a manual scoring (questions asked, defects caught, values from actual output, reverts, checks per item) into a recorded sheet per run in `evals/results/`, then automate the parts that proved stable. | after P0a | two P0a runs recorded with the same sheet |
| R5 | **One advisory mutation run.** Run PIT once (command-line, not in the build) on `oracle`, `state`, `run`, `engine.Triage`. Fix tests whose surviving mutants show a missing assertion; record the score in this file. Decide on a gate only if the score is low and stays low. | half a day | score recorded; weak tests fixed or listed |

## Not doing, and why

| Suggestion | Why not |
|---|---|
| Batch / event-stream / contract fixtures now | Those capabilities are explain-only in P0; add each fixture with the phase that activates it. |
| A multi-module dirty fixture | Multi-module is unsupported by decision (Q-R7). |
| A Windows coverage gate and an exemption list | Same code is gated on Linux; Windows-only paths are run by the Windows job. |
| A permanent PIT gate in every build | Unknown score, real build cost; R5 first. |
| More tests for `GtaCli` command classes | Thin delegation to `Engine`, already run end to end by `evals/e2e/run.sh`. |

## Status

| Id | Status | Result |
|---|---|---|
| R1 | **prepared; the release itself is the platform team's** | `docs/guides/releasing.md`. Rehearsed here: `mvn deploy` at `0.1.0` into a file repository publishes five artifacts (`gta-cli`, `gta-junit`, `gta-core`, `gta-harness-gen`, `gta-parent`; the quickstart said two, now fixed); the deployed `gta-cli-0.1.0.jar` prints its version and `init` pins its URL and sha256. Not done: the version bump, the Nexus upload, the tag and the GitHub Release. |
| R2 | done | `init`/`upgrade` refuse a lone, reversed or duplicated gta marker and write nothing (`InstallerTest`). |
| R3 | done | `DoctorChecks` 74/94 branches (79 %, was 38 %), lines 95 %. The rest are the Maven-on-PATH and Windows branches, which depend on the machine. |
| R4 | after P0a | – |
| R5 | done | see below |

### R5: mutation run on `oracle`, `state`, `run`, `engine.Triage`

PIT 1.19.6 with the JUnit 5 plugin, run from the command line, not added to the build.

| | Mutants | Killed | No coverage | Survived | Test strength | Score |
|---|---|---|---|---|---|---|
| before | 338 | 251 | 45 | 42 | 85.7 % | 74.3 % |
| after | 338 | 271 | 43 | 24 | 91.9 % | 80.2 % |

What the survivors showed, and what was done:

- **Oracle lock gaps (fixed with tests):** no test proved that `@ParameterizedTest`, `@RepeatedTest`, `@TestFactory`
  and `@Tags` count as scenario methods while a tagged helper does not; that a request body or path is never an
  expected value; that a matcher inside `andExpect` is locked with it; that fields, `@BeforeAll` and `@Sql` scripts
  are inputs while another scenario's test is not.
- **Auto-correction (fixed with tests):** nothing checked that a corrected expectation also drops the old locked
  statement and refreshes the plan's scenario revision (without it, the next plan sync would see a changed scenario).
- **Reports (fixed with tests):** a report with a `DOCTYPE` is refused; only `TEST-*.xml` files are read.
  `StateStore.peekPlan` returns the saved plan.
- **Redundant code (removed):** the `@Sql` loop in `AssertionScanner.scan` repeated what the loop over the method's
  string literals already does.
- **Accepted:** the messages that triage returns to the agent (replaced by an empty map, the state is still right);
  boundary mutants that cannot change a result (`i > 0` vs `i >= 0` on a name that never starts with `(`);
  each of the two XML hardening features blocks external entities alone, so removing one is not observable.

Decision: **no mutation gate.** The score is good, the remaining survivors are accepted above, and a PIT run takes
minutes. Run it again by hand when the oracle lock or triage changes.
