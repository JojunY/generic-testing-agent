# Changelog

All notable changes to gta. Versions follow SemVer; the version lives in the root `pom.xml`.

## [Unreleased] — 0.1.0 (P0)

### Fixed (code review)
- A reviewer `reject` no longer fails with an illegal transition.
- Items adopted after a rebuild get a token, so they can be repaired instead of deadlocking.
- The scope check sees commits made outside gtaw and the source of a rename. Such commits go to a human.
- Corrupt working-state files are kept and reported (`STATE_CORRUPT`) instead of being silently reset.
- A second relock by the same person is accepted by the gate. Relocks now carry `relockedAt`.
- `init`/`upgrade` read JSONC, never rewrite files that contain comments, and write nothing if an input cannot be parsed.
- An `@KnownDefect` added without a human's confirmation is refused. Adopted pins stay `defect-found`.
- Per-run temporary gcloud directories are deleted.

### Changed
- `Engine` is split into `SpecWorkflow`, `ItemWorkflow`, `SessionManager`, `SessionEnd`, `HumanPrompts` and `EscalationWriter` behind the same facade.
- `AtomicFiles` and `Yaml` are split out of `Json`. Gate concern codes and new environment and state codes are in `ErrorCode`.
- `--format` is `json|human`.
- `plan.md` moved to `docs/design/` and `tools/` to `scripts/`.

### Added
- `gtaw` CLI (`gta-cli`):
  - commands: `init`, `upgrade`, `doctor`, `next`, `check`, `submit`, `answer` (with `--file`), `status`, `abort`, `run`, `verify`, `gate` (and `gate waive`), `relock`, `report`;
  - stubs for `mutate` (P2) and `env` (P4);
  - `gtaw` / `gtaw.cmd` wrappers that download the pinned jar from Nexus and verify its sha256.
- Spec-first loop (`gta-core`):
  - OpenSpec-compatible specs with EARS requirements;
  - question rounds and approvals recorded with the human's words;
  - planning, briefs;
  - monotonic oracle lock: request inputs are watched, expected values are locked;
  - change check with revert;
  - failure triage by cause chain;
  - known-defect pinning, stability runs;
  - a base-anchored gate and a traceability report.
- `gta-junit`: `@KnownDefect` (strict expected failure with a fingerprint) and the scenario results listener.
- Harness content:
  - 4 agents, the `/gta` command, guardrails, 17 skills;
  - a capability registry (REST APIs active in P0; other capabilities explain-only until their phase).
- `gta-harness-gen`: lints `core/`; generates `copilot/` (VS Code and IntelliJ Copilot) and `opencode/`; `--check` for CI.
- Evals:
  - `order-service` fixture (Spring Boot 4.1) with seeded defects;
  - a scripted end-to-end run covering regression detection and the known-defect lifecycle.
