#!/usr/bin/env bash
# Try gta in five minutes, without Nexus: copies the sample service (evals/fixtures/order-service, a small Spring Boot
# service with seeded defects) to a folder of its own and installs the harness there, pinned to the jar built from this
# checkout. Nothing outside that folder and ~/.m2 is touched.
#
# Needs: JDK 17+, git, Maven for the sample service. Usage: scripts/try.sh [target-dir] [copilot|opencode]
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="${1:-$HOME/gta-try/order-service}"
PLATFORM="${2:-copilot}"
JAR="$REPO/toolkit/gta-cli/target/gta-cli.jar"

case "$PLATFORM" in copilot|opencode) ;; *) echo "platform must be copilot or opencode, not $PLATFORM" >&2; exit 2 ;; esac
if [ -e "$TARGET" ]; then
  echo "$TARGET already exists: remove it or name another folder" >&2
  exit 2
fi

if [ ! -f "$JAR" ]; then
  echo "== building gta (once; this also puts gta-junit into ~/.m2 for the sample service)"
  (cd "$REPO" && ./mvnw -B -q install -DskipTests)
fi
if command -v sha256sum >/dev/null 2>&1; then SHA=$(sha256sum "$JAR" | cut -d' ' -f1); else SHA=$(shasum -a 256 "$JAR" | cut -d' ' -f1); fi

echo "== sample service in $TARGET"
mkdir -p "$TARGET"
cp -r "$REPO/evals/fixtures/order-service/." "$TARGET/"
cd "$TARGET"
rm -rf target
printf 'target/\n' > .gitignore
git init -q -b main
git add -A
git -c user.name="${GIT_AUTHOR_NAME:-gta-try}" -c user.email="${GIT_AUTHOR_EMAIL:-gta-try@example.com}" commit -qm "order-service sample"

echo "== installing the $PLATFORM harness (the wrapper is pinned to the local jar and its sha256)"
java -jar "$JAR" --root . init --platform "$PLATFORM" --distribution-url "file://$JAR" --distribution-sha256 "$SHA" >/dev/null
git add -A
git -c user.name="${GIT_AUTHOR_NAME:-gta-try}" -c user.email="${GIT_AUTHOR_EMAIL:-gta-try@example.com}" commit -qm "gta init"
git checkout -q -b feature/gta-tests

echo "== ./gtaw doctor"
./gtaw --format human doctor || true

cat <<EOF

Ready. Next:
  1. Open $TARGET in $([ "$PLATFORM" = copilot ] && echo "VS Code or IntelliJ (GitHub Copilot)" || echo "OpenCode").
  2. Type /gta and reply OK to start with the riskiest flow.
  3. docs/business-rules.md states rules the code breaks: a good run asks you about them or pins them as defects.
To start over, delete $TARGET and run this script again.
EOF
