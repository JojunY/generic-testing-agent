#!/usr/bin/env bash
# Installs gta into a service on this machine without Nexus: the wrapper is pinned to the jar built from this checkout
# (a file:// URL and its sha256), and gta-junit comes from ~/.m2. It is the same gta as a Nexus install; only where the
# jar comes from differs. For one developer's machine: a teammate or CI cannot resolve a local path, so a team installs
# from a shared URL instead (docs/guides/quickstart.md).
#
# Needs: JDK 17+, git. Usage: scripts/install-local.sh <service-dir> [copilot|opencode]
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
SERVICE="${1:-}"
PLATFORM="${2:-copilot}"
JAR="$REPO/toolkit/gta-cli/target/gta-cli.jar"

[ -n "$SERVICE" ] || { echo "usage: scripts/install-local.sh <service-dir> [copilot|opencode]" >&2; exit 2; }
case "$PLATFORM" in copilot|opencode) ;; *) echo "platform must be copilot or opencode, not $PLATFORM" >&2; exit 2 ;; esac
[ -d "$SERVICE" ] || { echo "$SERVICE is not a folder" >&2; exit 2; }
SERVICE="$(cd "$SERVICE" && pwd)"
git -C "$SERVICE" rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo "$SERVICE is not a git repository" >&2; exit 2; }

if [ ! -f "$JAR" ]; then
  echo "== building gta (once; this also puts gta-junit into ~/.m2)"
  (cd "$REPO" && ./mvnw -B -q install -DskipTests)
fi
if command -v sha256sum >/dev/null 2>&1; then SHA=$(sha256sum "$JAR" | cut -d' ' -f1); else SHA=$(shasum -a 256 "$JAR" | cut -d' ' -f1); fi

echo "== installing the $PLATFORM harness into $SERVICE (wrapper pinned to $JAR)"
# a file:// URL: a space, # or % in the checkout path must be percent-encoded, or curl rejects it
URL="file://$(printf '%s' "$JAR" | sed 's/%/%25/g; s/ /%20/g; s/#/%23/g')"
OUT=$(java -jar "$JAR" --root "$SERVICE" init --platform "$PLATFORM" --distribution-url "$URL" --distribution-sha256 "$SHA") \
  || { echo "$OUT" >&2; exit 1; }
# init's report names the settings keys it could not merge into a file with comments (mergeManually) and conflicts
echo "$OUT"

echo "== ./gtaw doctor"
(cd "$SERVICE" && ./gtaw --format human doctor) || true

cat <<EOF

Installed. Next:
  1. Review and commit the new files in $SERVICE (git status shows them).
  2. Open it in $([ "$PLATFORM" = copilot ] && echo "VS Code or IntelliJ (GitHub Copilot)" || echo "OpenCode") and type /gta.
This install works on this machine only. For the team, run init again with a shared URL (docs/guides/quickstart.md, step 2).
EOF
