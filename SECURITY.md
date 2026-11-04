# Security policy

## Reporting a vulnerability

Do not open a public issue. Report it privately through GitHub's "Report a vulnerability" (Security tab) or to the
maintainers of this repository. Include the affected version (`./gtaw --version`), steps to reproduce and the impact.
You will get an answer within five working days.

## What gta protects

- `gtaw` downloads only a jar whose sha256 is pinned in `.gta/wrapper/gta-wrapper.properties`, and refuses to run
  otherwise. Nexus credentials are read from `GTA_NEXUS_USER` / `GTA_NEXUS_PASSWORD` and never put on a command line.
- Test runs get an isolated, empty cloud-credential environment (plan §13), so agent-written tests cannot reach real
  cloud projects.
- Agents never run git; only commits recorded in gtaw's commit ledger are trusted by the scope check.

## Supported versions

Only the latest release receives fixes.
