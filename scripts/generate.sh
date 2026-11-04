#!/usr/bin/env sh
# Regenerates copilot/ and opencode/ from core/ and platforms/ (plan §11.3). Pass --check to verify only (CI).
set -eu
cd "$(dirname "$0")/.."
mvn -q -B -pl toolkit/gta-harness-gen -am package -DskipTests
java -jar toolkit/gta-harness-gen/target/gta-harness-gen.jar generate --repo . "$@"
