# Code review, round 2: verification and remediation

> Scope: the whole code base on `origin/main` (after PR #1) and the PR #2 branch.
>
> Method: every finding was first checked against the code, then fixed, then verified with unit tests and `evals/e2e/run.sh`.
>
> Status legend:
> - ✅ fixed and covered by tests;
> - ➖ by design after checking; only the documentation was corrected;
> - 🔜 deferred, with the reason.

## 1. Main findings (15, all accurate)

| # | Finding | Verified | Fix | Evidence |
|---|---|---|---|---|
| 1 | The approval pattern used `find()`, so "不批准 …" or "don't approve …" counted as an approval | yes | an approval must be the whole reply, `^(approve\|批准) <flow>@R<n>$`; any other reply that mentions approve is recorded as a change request for the analyst | `AnswerParserTest`, `EngineTest` (negated approval) |
| 2 | A Maven timeout threw a retryable error before state was saved, so a hanging test could be resubmitted forever | yes | `MavenRunner` records the timeout as `RunResult.timedOut`; `submit` counts it as `TEST_TIMEOUT`, and the same failure twice goes to a person | `EngineTest.shouldCountATimedOutRun…` |
| 3 | A clean session end reset TRIAGING items to PENDING, so stability failures were never triaged | yes | items are reset only on takeover or idle timeout, and only AUTHORING/REPAIRING | `EngineTest.shouldKeepItemsWaitingForTriage…` / `…ResetOnlyEditingItemsOnTakeover` |
| 4 | Marking a spec stale did not refresh the snapshot; without a snapshot, human answers were reported as agent violations | yes | every gtaw write to a spec goes through `writeSpec`, which refreshes the snapshot; without a snapshot the committed version (`git show HEAD:`) is the baseline | `EngineTest` (approval flow), e2e |
| 5 | A spec edited after it was submitted could still be approved | yes | approval compares the file with the snapshot; on a difference it is refused and the spec goes back to the analyst as a new revision | `EngineTest.shouldNotApproveASpecEditedAfterItWasShown` |
| 6 | An unparseable oracle shard was read as `{}`, overwriting other locks and disabling the gate | yes | fail closed: `CONFIG_ORACLE_SHARD_INVALID`, and `GATE_SHARD_UNREADABLE` in the gate | `OracleShardTest`, `GateTest` |
| 7 | `relock` kept the old tokens and ran without the lock | yes | under `WorkLock` it re-derives tokens from the spec's THEN and updates the scenario revision; non-scenario ids are refused | `EngineTest.shouldRelockOnTheSpecsExpectedValues` |
| 8 | `submit --review` needed no token | yes | review nodes carry a token; a verdict needs the current token and is accepted once per round | `EngineTest.shouldRefuseAReviewVerdict…` |
| 9 | `lastSeenAt` was stamped when a command started, so a long submit looked idle | yes | stamped in `StateStore.save` | `StateStoreTest` |
| 10 | The spec loop was unbounded; `maxQuestionsPerRound`/`maxClarifyRounds` were unused | yes | new `spec.maxSubmitAttempts` (default 3) escalates; at most 5 questions per round; no new questions once the clarify rounds are used up | unit tests, `GtaConfigTest` |
| 11 | "Retry" did not reset the check budget | yes | retry resets checks too | `EngineTest.shouldGiveARetriedItem…` |
| 12 | The wrappers downloaded and ran a jar without a sha256 | yes | `gtaw` and `gtaw-download.ps1` fail closed; `doctor` has a wrapper check | `sh -n`, doctor |
| 13 | Any commit with a `Gta-*:` line counted as gtaw's, which anyone can forge | yes | a `CommitLedger` records the SHAs gtaw creates; only those are trusted | `ChangeCheckerTest.shouldNotTrustAForgedGtawTrailer`, `EngineTest` |
| 14 | `abort` deleted the only backups in `recover/` | yes | `abort` keeps `recover/` and says so | code review |
| 15 | `upgrade` ignored files a new version no longer ships | yes | untouched obsolete files are removed; locally edited ones are kept and listed | `InstallerTest` |

## 2. Rule violations (against AGENTS.md)

| Rule | Verdict | Fix |
|---|---|---|
| Every file write goes through `AtomicFiles` | accurate, 3 places | snapshots, `maven.log` and backups (new `AtomicFiles.copy`) |
| No hard-coded paths | accurate | `GtaPaths.snapshots/gtaResults/verifyLatest/gateLatest/commitLedger`; escalation paths and `testSourceRel` used throughout; duplicate `Briefs.itemKey` removed |
| New behaviour needs a unit test | accurate | `FakeTestRunner` and `EngineTest` (14 cases), `IsolatedEnvTest` and others; unit tests grew from 42 to 80 |
| Error codes each carry a hint | ➖ partly | hints are required where a `GtaException` is thrown, by design. The AGENTS.md wording was corrected; `retryable` can be overridden per code (`STATE_BUDGET_EXHAUSTED` is not retryable); the concern category exiting 0 is now written down |

## 3. Architecture

| Finding | Fix |
|---|---|
| Cycles through `EngineContext` (ItemWorkflow ↔ SessionEnd, SessionManager → ItemWorkflow) | `dispatch` returns `Dispatch.sessionOver` and the Engine calls SessionEnd; `planItem` moved to `StateStore`. No cycles remain |
| No test-run component | new `run.TestRunner` interface (Maven, or a fake in tests) and `engine.TestRuns` (JavaIndex parsed once per invocation) |
| AGENTS.md did not match the code | corrected, with new rules: the ledger, `writeSpec`, fail closed |
| Statuses, types and verdicts are strings | 🔜 P1: it needs a migration of persisted JSON; the risk outweighs the benefit now |

## 4. Efficiency and dead code

- **Efficiency:**
  - JavaIndex is cached;
  - `syncPlan` reads the shard once;
  - the documented-source downgrade lints once and matches by scenario id (title-substring mismatches are gone);
  - `commitsSince` makes a single git call;
  - the spec path pattern is precompiled.
- **Dead code removed:**
  - the unreachable branch;
  - `ENV_MAVEN_UNAVAILABLE`, `POLICY_PROTECTED_PATH`, `GATE_KNOWN_DEFECT_MISMATCH`;
  - unused configuration `spec.source`, `environments.allowCloud/gcpProject/region`;
  - `WorkState.sessionRuns`, `Engine.paths()/config()`, `GtaPaths.session()/skills()/mainResources()`.
- **Put back to use:**
  - `STATE_NOTHING_PENDING` for `answer`;
  - `dispatch`, now echoed in agent nodes for the orchestrator.
- **Fully qualified class names** replaced by imports.
- **Configuration:** unknown keys fail with `CONFIG_INVALID`, and `$schema` is allowed. The schema matches the code (`mutation.mode` had the wrong values).

## 5. Smaller bugs (all fixed)

- `confirmedPin` reads the committed record.
- `commitItem` does not commit the developer's own uncommitted edits (such as `pom.xml`) and says so; uncommitted test code left by earlier items of the same flow under `src/test/` is committed as usual. The first version also skipped the shared test class; the e2e rebuild scenario caught it.
- The gate raises `GATE_SCENARIOS_UNLOCKED` when a spec with locked scenarios is deleted or not approved.
- `gate waive` reports a bad date as a usage error.
- `status` and `report` are read-only and never move files.
- `oracleReview` is an organisation floor; question and round limits are capped.
- Found while fixing: a change request at the approval prompt used to fail as "unparseable"; it now goes back to the analyst.

## 6. Layout and naming

- `evals/e2e/agent/*.method.java` and `*.head.java` became `*.java.txt`: they are fragments, and IDEs should not treat them as sources.
- The status of `docs/design/plan.md` changed from "design final (not implemented)" to "P0 implemented", and the §11.3 tree matches the repository.
- The duplicate sync step in CI was removed; `RepositoryHarnessTest` covers it.
- **Leftover files:** the tree is clean, with no caches, logs, IDE files or TODOs; the ignored `target/` folders are not in the repository.

## 7. Decisions taken after this review

| Question | Decision | Done |
|---|---|---|
| Language of documentation and prompts | English everywhere; README and the design also have a `*.zh-CN.md` copy with a language switch; users may reply in Chinese (`批准`, `其余默认`, `= 代码现状` are accepted aliases) | ✅ a repository test keeps Chinese out of every other file |
| Maven groupId and Java packages | stay `com.gta` until a company domain is chosen | ✅ documented in §9.1 and §16 of the design |
| Diagrams | the explanatory diagrams of README and the design follow cathrynlavery/diagram-design: one palette with light and dark skins, a single accent, a 4px grid, hairline strokes, no shadows. `scripts/diagrams/render.py` renders them to `docs/diagrams/*.svg`, and pages use `<picture>` so GitHub picks the theme. Spec diagrams stay Mermaid (parsed and linted by gtaw). Agents365-ai/drawio-skill was used as a reference only | ✅ architecture, loop, item states, spec lifecycle; CI checks they are up to date |
