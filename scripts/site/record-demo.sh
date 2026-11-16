#!/usr/bin/env bash
# Records the gtaw run that the introduction site replays (docs/site): one flow of the lending sample, borrow.book,
# from /gta to a pinned defect. A script plays the agents, as in evals/e2e; everything gtaw prints is real. The
# recording (every gtaw output, the spec, the briefs, the test, the commits, the report) is written to
# docs/site/assets/js/run-data.js. Run it again after a change to what gtaw says, then review the diff.
#
# With --lang zh-CN gtaw speaks Chinese (output.language) and the recording goes to run-data.zh-CN.js, for the Chinese
# page; what the scripted agents write stays English.
#
# Needs: the same as evals/e2e/run.sh (the toolkit built and installed).
# Usage: scripts/site/record-demo.sh [--lang zh-CN] [work-dir]
set -euo pipefail

LANGUAGE=en
if [ "${1:-}" = "--lang" ]; then
  LANGUAGE="$2"
  shift 2
fi

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
AGENT="$REPO/scripts/site/demo"                      # what the scripted analyst and test author write
SEED="$REPO/evals/e2e/scripted-output/lending"       # the seed test of the lending sample, shared with the e2e
WORK="${1:-$(mktemp -d)}"
DATA="$REPO/docs/site/assets/js/run-data.js"
[ "$LANGUAGE" = en ] || DATA="$REPO/docs/site/assets/js/run-data.$LANGUAGE.js"
REC="$(mktemp)"
P=""
export GTA_CLI_JAR="$REPO/toolkit/gta-cli/target/gta-cli.jar"
export GIT_AUTHOR_NAME=gta-demo GIT_AUTHOR_EMAIL=gta-demo@example.com GIT_COMMITTER_NAME=gta-demo GIT_COMMITTER_EMAIL=gta-demo@example.com
[ -f "$GTA_CLI_JAR" ] || { echo "build the toolkit first: ./mvnw -B install -DskipTests"; exit 2; }
# shellcheck source=../../evals/e2e/lib.sh
source "$REPO/evals/e2e/lib.sh"

# every gtaw call is recorded under the key in $K, with its arguments, exit code and output
gtaw() {
  local out rc=0
  out=$(./gtaw "$@" 2>/dev/null) || rc=$?
  python3 - "$REC" "${K:-}" "$rc" "$out" "$@" <<'PY'
import json, sys
rec, key, rc, out, args = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4], sys.argv[5:]
try:
    parsed = json.loads(out)
except ValueError:
    parsed = {"raw": out}
with open(rec, "a", encoding="utf-8") as f:
    f.write(json.dumps({"k": key, "gtaw": args, "exit": rc, "out": parsed}) + "\n")
PY
  printf '%s' "$out"
  return $rc
}
# what a person or a (scripted) agent did between two gtaw calls
act() { # act <key> <actor> <what>
  python3 -c 'import json,sys; open(sys.argv[1],"a",encoding="utf-8").write(json.dumps({"k":sys.argv[2],"actor":sys.argv[3],"did":sys.argv[4]})+"\n")' "$REC" "$1" "$2" "$3"
}
keep() { # keep <name> <file>: a file as it is at this point of the run
  mkdir -p "$REC.files" && cp "$2" "$REC.files/$1"
}

step "prepare $WORK"
rm -rf "$WORK" && mkdir -p "$WORK"
cp -r "$REPO/evals/fixtures/lending-service/." "$WORK/"
cd "$WORK"
rm -rf target
printf 'target/\n' > .gitignore
git init -q -b main && git add -A && git commit -qm "lending-service"
gta_init copilot
# this run shows the common case, JUnit tests; the sample's Cucumber suite stays the team's own (plan §7.10)
printf '{ "build": { "cucumber": "off" }, "output": { "language": "%s" } }\n' "$LANGUAGE" > .gta/config.json
git add -A && git commit -qm "gta init"
git checkout -q -b feature/gta-tests
SPEC=.gta/specs/borrow/book.spec.md
TEST=src/test/java/com/example/lending/borrow/BorrowControllerIT.java
S01="borrow.book/s01#component-01"
S02="borrow.book/s02#component-01"

step "/gta: the flows gtaw found"
act type-gta you "/gta"
OUT=$(K=pick gtaw next)
jget "$OUT" 'd["display"]' | grep -q "2. borrow.book" || fail "borrow.book is not the second flow of the list: $OUT"

step "2: the analyst writes the spec, with one question"
act pick-flow you "2"
OUT=$(K=analyze gtaw next --target 2)
expect "$(jget "$OUT" 'd["node"]')" analyze "first node"
expect "$(jget "$OUT" 'd["flowId"]')" borrow.book "flow of the list number"
keep brief-analyze.md "$(jget "$OUT" 'd["brief"]')"
mkdir -p .gta/specs/borrow && cp "$AGENT/book.spec.md" "$SPEC"
act write-spec analyst "$SPEC"
keep spec-r1.md "$SPEC"
OUT=$(K=spec-r1 gtaw submit --spec borrow.book --token "$(jget "$OUT" 'd["token"]')")
expect "$(jget "$OUT" 'd["rev"]')" R1 "spec revision"
OUT=$(K=question gtaw next)
expect "$(jget "$OUT" 'd["type"]')" answer "question round"

step "OK: the recommended answer; the analyst rewrites the THEN"
act answer you "OK"
K=answered gtaw answer "OK" >/dev/null
grep -q 'id=borrow.book/s02 source=confirmed' "$SPEC" || fail "s02 not confirmed by gtaw"
OUT=$(K=analyze-answers gtaw next)
expect "$(jget "$OUT" 'd["node"]')" analyze "revision node"
sed -i 's/the response status is `201` and the borrow status is `BOOKED`/the response status is `503` and the borrow status is `PENDING_LOCATE`/' "$SPEC"
act apply-answer analyst "$SPEC"
OUT=$(K=spec-r2 gtaw submit --spec borrow.book --token "$(jget "$OUT" 'd["token"]')")
expect "$(jget "$OUT" 'd["rev"]')" R2 "revised spec"
OUT=$(K=approval gtaw next)
expect "$(jget "$OUT" 'd["type"]')" approve "approval prompt"

step "OK: approved; the seed test"
act approve you "OK"
K=approved gtaw answer "OK" >/dev/null
keep spec.md "$SPEC"
OUT=$(K=author-seed gtaw next)
expect "$(jget "$OUT" 'd["itemId"]')" "borrow#foundation-01" "seed item"
add_gta_junit pom.xml
mkdir -p src/test/java/com/example/lending/support src/test/resources/META-INF src/test/resources/config
cp "$SEED/LendingApplicationIT.java.txt" src/test/java/com/example/lending/LendingApplicationIT.java
cp "$SEED/FakeSecretManager.java.txt" src/test/java/com/example/lending/support/FakeSecretManager.java
cp "$SEED/spring.factories.txt" src/test/resources/META-INF/spring.factories
cp "$SEED/config-application.properties.txt" src/test/resources/config/application.properties
act write-seed test-author "src/test/java/com/example/lending/LendingApplicationIT.java"
OUT=$(K=seed-passed gtaw submit --item "borrow#foundation-01" --token "$(jget "$OUT" 'd["token"]')")
jget "$OUT" 'd["result"]' | grep -q '^passed' || fail "seed: $OUT"

step "s01: written, run, locked, reviewed"
OUT=$(K=author-s01 gtaw next)
expect "$(jget "$OUT" 'd["itemId"]')" "$S01" "author node"
keep brief-author.md "$(jget "$OUT" 'd["brief"]')"
mkdir -p "$(dirname "$TEST")" && cp "$AGENT/BorrowControllerIT.java.txt" "$TEST"
act write-test test-author "$TEST"
K=s01-submitted gtaw submit --item "$S01" --token "$(jget "$OUT" 'd["token"]')" >/dev/null
OUT=$(K=review-s01 gtaw next)
expect "$(jget "$OUT" 'd["kind"]')" oracle-review "review of s01"
act accept reviewer "accept: the test asserts the status 400 on the response"
OUT=$(K=s01-passed gtaw submit --review "$S01" --token "$(jget "$OUT" 'd["token"]')" --verdict accept --reason "the test asserts the status 400 on the response")
jget "$OUT" 'd["result"]' | grep -q '^passed' || fail "s01: $OUT"

step "s02: the confirmed expectation fails on today's code"
OUT=$(K=author-s02 gtaw next)
expect "$(jget "$OUT" 'd["itemId"]')" "$S02" "author node"
OUT=$(K=s02-failed gtaw submit --item "$S02" --token "$(jget "$OUT" 'd["token"]')")
expect "$(jget "$OUT" 'd["code"]')" TEST_ASSERTION_FAILED "s02 failure"
OUT=$(K=triage gtaw next)
expect "$(jget "$OUT" 'd["kind"]')" triage "triage node"
keep brief-triage.md "$(jget "$OUT" 'd["brief"]')"
REASON="InventoryClient#inventoryDown reports the shares as reserved when inventory-service did not answer"
act verdict reviewer "product-defect: $REASON"
K=triaged gtaw submit --review "$S02" --token "$(jget "$OUT" 'd["token"]')" --verdict product-defect --reason "$REASON" >/dev/null
OUT=$(K=defect gtaw next)
expect "$(jget "$OUT" 'd["type"]')" defect "defect decision"

step "OK: a defect; the test is pinned"
act confirm-defect you "OK"
K=defect-confirmed gtaw answer "OK" >/dev/null
OUT=$(K=author-pin gtaw next)
expect "$(jget "$OUT" 'd["itemId"]')" "$S02" "pin node"
keep brief-pin.md "$(jget "$OUT" 'd["brief"]')"
FP=$(grep -o 'failure = "[0-9a-f]*"' "$(jget "$OUT" 'd["brief"]')" | head -1 | grep -o '[0-9a-f]\{12\}')
sed -i "s|    @DisplayName(\"\[borrow.book/s02\] inventory-service cannot be reached\")|&\n    @KnownDefect(id = \"borrow.book/s02\", failure = \"$FP\")|" "$TEST"
act pin test-author "@KnownDefect(id = \"borrow.book/s02\", failure = \"$FP\")"
OUT=$(K=pinned gtaw submit --item "$S02" --token "$(jget "$OUT" 'd["token"]')")
jget "$OUT" 'd["result"]' | grep -q '^defect-found' || fail "pin: $OUT"

step "session end: stability runs, the gate, the report"
OUT=$(K=done gtaw next)
expect "$(jget "$OUT" 'd["node"]')" done "final node"
K=status gtaw status >/dev/null
keep test.java "$TEST"
keep report.md "$(jget "$OUT" 'd["report"]')"
keep cases.md .gta/specs/borrow/book.cases.md
keep shard.json "$(git ls-files '.gta/**' | grep -i 'oracle' | grep 'book' | head -1)"
git log --reverse --format='%h %s' main..HEAD > "$REC.files/commits.txt"
git diff --quiet || fail "the work tree is dirty"

step "write $DATA"
python3 - "$REC" "$DATA" "$(cd "$REPO" && git rev-parse --short HEAD)" "$GTA_VERSION" "$LANGUAGE" <<'PY'
import datetime, json, pathlib, sys
rec, data, commit, version, language = sys.argv[1:]
events = [json.loads(line) for line in pathlib.Path(rec).read_text(encoding="utf-8").splitlines() if line.strip()]
files = {f.name: f.read_text(encoding="utf-8") for f in sorted(pathlib.Path(rec + ".files").iterdir())}
run = {
    "meta": {
        "recorded": datetime.date.today().isoformat(),
        "gta": version,
        "commit": commit,
        "language": language,
        "service": "evals/fixtures/lending-service",
        "flow": "borrow.book",
        "how": "scripts/site/record-demo.sh: a script plays the agents, every gtaw output is real",
    },
    "events": events,
    "files": files,
}
header = (
    "/* Generated by scripts/site/record-demo.sh from a real gtaw run. Do not edit: record it again. */\n"
)
pathlib.Path(data).parent.mkdir(parents=True, exist_ok=True)
pathlib.Path(data).write_text(
    header + "window.GTA_RUN = " + json.dumps(run, indent=1, ensure_ascii=True) + ";\n", encoding="utf-8"
)
print("events", len(events), "files", sorted(files), "bytes", pathlib.Path(data).stat().st_size)
PY

printf '\nRECORDED (%s)\n' "$WORK"
