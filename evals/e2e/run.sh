#!/usr/bin/env bash
# Scripted end-to-end run of gtaw against evals/fixtures/order-service (plan §12, P0 exit criteria).
#
# The agentic nodes are played by canned outputs in evals/e2e/scripted-output/ (what a correct analyst/author/reviewer would
# produce), so this checks gtaw's deterministic side: spec lint and rounds, approval commits, planning, the oracle
# lock, the change check, triage, @KnownDefect pinning, verify, the gate and regression detection.
#
# Needs: java 17+, mvn, git, python3; the toolkit installed in ~/.m2 (mvn -B install -DskipTests at the repo root).
# Usage: evals/e2e/run.sh [work-dir]
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
AGENT="$REPO/evals/e2e/scripted-output"
WORK="${1:-$(mktemp -d)}"
export GTA_CLI_JAR="$REPO/toolkit/gta-cli/target/gta-cli.jar"
export GIT_AUTHOR_NAME=gta-e2e GIT_AUTHOR_EMAIL=gta-e2e@example.com GIT_COMMITTER_NAME=gta-e2e GIT_COMMITTER_EMAIL=gta-e2e@example.com
[ -f "$GTA_CLI_JAR" ] || { echo "build the toolkit first: mvn -B install -DskipTests"; exit 2; }

step() { printf '\n== %s\n' "$*"; }
fail() { echo "E2E FAILED: $*" >&2; exit 1; }
gtaw() { ./gtaw "$@" 2>/dev/null; }
# jget <json> <python expression on d>; the expressions are literals of this script, never input
jget() { python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(eval(sys.argv[2]))' "$1" "$2"; }
expect() { # expect <actual> <expected> <what>
  [ "$1" = "$2" ] || fail "$3: expected '$2', got '$1'"
  echo "ok   $3 = $2"
}
TEST=src/test/java/com/example/order/api/OrderControllerIT.java
add_method() { python3 - "$TEST" "$1" <<'EOF'
import sys
path, part = sys.argv[1], open(sys.argv[2]).read()
s = open(path).read().rstrip()
assert s.endswith("}")
open(path, "w").write(s[:-1].rstrip() + "\n" + part + "}\n")
EOF
}

step "prepare $WORK"
rm -rf "$WORK" && mkdir -p "$WORK"
cp -r "$REPO/evals/fixtures/order-service/." "$WORK/"
cd "$WORK"
rm -rf target
printf 'target/\n' > .gitignore
git init -q -b main && git add -A && git commit -qm "order-service fixture"
java -jar "$GTA_CLI_JAR" --root . init --platform copilot >/dev/null 2>&1
git add -A && git commit -qm "gta init"
git checkout -q -b feature/gta-tests

step "analyze: spec R1 with one high-impact question"
OUT=$(gtaw next --mode flow --target order.create-order)
expect "$(jget "$OUT" 'd["node"]')" analyze "first node"
TOKEN=$(jget "$OUT" 'd["token"]')
mkdir -p .gta/specs/order && cp "$AGENT/create-order.spec.md" .gta/specs/order/create-order.spec.md
OUT=$(gtaw submit --spec order.create-order --token "$TOKEN")
expect "$(jget "$OUT" 'd["rev"]')" R1 "spec revision"
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["type"]')" answer "question round"

step "human answers R1: 1B (the threshold discount is a bug)"
gtaw answer "R1: 1B" >/dev/null
grep -q 'id=order.create-order/s03 source=confirmed' .gta/specs/order/create-order.spec.md || fail "s03 not confirmed by gtaw"
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["node"]')" analyze "revision node"
TOKEN=$(jget "$OUT" 'd["token"]')
sed -i 's/discount `0.00` and total `100.00`/discount `10.00` and total `90.00`/' .gta/specs/order/create-order.spec.md
OUT=$(gtaw submit --spec order.create-order --token "$TOKEN")
expect "$(jget "$OUT" 'd["rev"]')" R2 "revised spec"
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["type"]')" approve "approval prompt"

step "human approves (ASCII form)"
gtaw answer "approve order.create-order@R2" >/dev/null
git log -1 --format=%s | grep -q "order.create-order R2 approved" || fail "approval not committed"
echo "ok   approval committed"

step "foundation: seed test"
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["itemId"]')" "order#foundation-01" "first item"
TOKEN=$(jget "$OUT" 'd["token"]')
python3 - <<'EOF'
s = open("pom.xml").read()
dep = """    <dependency>
      <groupId>com.gta</groupId>
      <artifactId>gta-junit</artifactId>
      <version>0.1.1-SNAPSHOT</version>
      <scope>test</scope>
    </dependency>
  </dependencies>"""
open("pom.xml", "w").write(s.replace("  </dependencies>", dep, 1))
EOF
cp "$AGENT/OrderApplicationIT.java.txt" src/test/java/com/example/order/OrderApplicationIT.java
OUT=$(gtaw submit --item "order#foundation-01" --token "$TOKEN")
jget "$OUT" 'd["result"]' | grep -q '^passed' || fail "foundation: $OUT"
echo "ok   foundation passed"

author() { # author <scenario> -> prints token of the author node
  local out; out=$(gtaw next)
  if [ "$(jget "$out" 'd["node"]')" = done ]; then
    echo "session ended ($(jget "$out" 'd["message"]')); starting the next one" >&2
    out=$(gtaw next)
  fi
  [ "$(jget "$out" 'd.get("itemId")')" = "order.create-order/$1#component-01" ] || fail "expected author node for $1: $out"
  jget "$out" 'd["token"]'
}
review_accept() {
  local out; out=$(gtaw next)
  expect "$(jget "$out" 'd["kind"]')" oracle-review "review kind for $1"
  out=$(gtaw submit --review "order.create-order/$1#component-01" --token "$(jget "$out" 'd["token"]')" --verdict accept --reason "asserts the THEN values")
  jget "$out" 'd["result"]' | grep -q '^passed' || fail "$1 not passed after review: $out"
  echo "ok   $1 passed and committed"
}

step "s01: oracle lock and change check"
ITEM="order.create-order/s01#component-01"; TOKEN=$(author s01)
mkdir -p "$(dirname "$TEST")" && { cat "$AGENT/OrderControllerIT.head.java.txt"; echo "}"; } > "$TEST" && add_method "$AGENT/s01.method.java.txt"
OUT=$(gtaw check --item "$ITEM" --token "$TOKEN")
expect "$(jget "$OUT" 'd["passed"]')" 1 "s01 check"
expect "$(jget "$OUT" '",".join(d["oracleLocked"])')" "201,0.00,50.00,CREATED" "locked oracle"
sed -i 's/value(50.00)/value(51.00)/' "$TEST"
OUT=$(gtaw check --item "$ITEM" --token "$TOKEN")
expect "$(jget "$OUT" 'd["oracleLock"]')" modified "tampered expected value"
sed -i 's/value(51.00)/value(50.00)/' "$TEST"
echo "// out of scope" >> src/main/java/com/example/order/OrderApplication.java
OUT=$(gtaw submit --item "$ITEM" --token "$TOKEN")
expect "$(jget "$OUT" 'd["code"]')" POLICY_OUT_OF_SCOPE_CHANGE "production edit"
git diff --quiet -- src/main || fail "production edit was not reverted"
echo "ok   production edit reverted"
TOKEN=$(author s01)
gtaw submit --item "$ITEM" --token "$TOKEN" >/dev/null
review_accept s01

step "s02"
ITEM="order.create-order/s02#component-01"; TOKEN=$(author s02)
add_method "$AGENT/s02.method.java.txt"
gtaw submit --item "$ITEM" --token "$TOKEN" >/dev/null
review_accept s02

step "s03: confirmed expectation exposes seeded defect D1"
ITEM="order.create-order/s03#component-01"; TOKEN=$(author s03)
add_method "$AGENT/s03.method.java.txt"
OUT=$(gtaw submit --item "$ITEM" --token "$TOKEN")
expect "$(jget "$OUT" 'd["code"]')" TEST_ASSERTION_FAILED "s03 failure"
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["kind"]')" triage "triage node"
gtaw submit --review "$ITEM" --token "$(jget "$OUT" 'd["token"]')" --verdict product-defect --reason "PricingService#discount uses > instead of >=" >/dev/null
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["type"]')" defect "defect decision"
gtaw answer A >/dev/null
OUT=$(gtaw next)
TOKEN=$(jget "$OUT" 'd["token"]')
FP=$(grep -o 'failure = "[0-9a-f]*"' "$(jget "$OUT" 'd["brief"]')" | head -1 | grep -o '[0-9a-f]\{12\}')
sed -i "s|    @DisplayName(\"\[order.create-order/s03\] order exactly at the threshold\")|&\n    @KnownDefect(id = \"order.create-order/s03\", failure = \"$FP\")|" "$TEST"
OUT=$(gtaw submit --item "$ITEM" --token "$TOKEN")
jget "$OUT" 'd["result"]' | grep -q '^defect-found' || fail "pin: $OUT"
echo "ok   s03 pinned with @KnownDefect ($FP)"

step "s04: the reviewer rejects the first oracle, the author resubmits"
ITEM="order.create-order/s04#component-01"; TOKEN=$(author s04)
add_method "$AGENT/s04.method.java.txt"
gtaw submit --item "$ITEM" --token "$TOKEN" >/dev/null
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["kind"]')" oracle-review "review kind for s04"
OUT=$(gtaw submit --review "$ITEM" --token "$(jget "$OUT" 'd["token"]')" --verdict reject --reason "also assert that nothing was stored")
jget "$OUT" 'd["result"]' | grep -q 'fix it' || fail "reject did not send s04 back to the author: $OUT"
echo "ok   reject sends the item back to the author"
TOKEN=$(author s04)
gtaw submit --item "$ITEM" --token "$TOKEN" >/dev/null
review_accept s04

step "session end: verify, gate, report"
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["node"]')" done "final node"
expect "$(jget "$OUT" 'd["gate"]')" concerns "gate on the feature branch"
grep -q "Product defects found" "$(jget "$OUT" 'd["report"]')" || fail "report lacks the known defect"
case "$(jget "$OUT" 'd["report"]')" in .gta/reports/*) echo "ok   session report is tracked in .gta/reports" ;; *) fail "report outside .gta/reports: $OUT" ;; esac
git ls-files --error-unmatch "$(jget "$OUT" 'd["report"]')" >/dev/null 2>&1 || fail "session report not committed"
LEDGER=.gta/specs/order/create-order.cases.md
expect "$(grep -c '^| order.create-order/s0' "$LEDGER")" 4 "ledger rows, one per scenario"
grep -q '^| order.create-order/s01 .*OrderControllerIT#.* | Pass .* | Active |$' "$LEDGER" || fail "s01 ledger row: $(grep s01 "$LEDGER")"
grep -q 'order.create-order' .gta/specs/INDEX.md || fail "INDEX.md lacks the flow"
echo "ok   report lists the known defect"

step "rebuild: lost working state, adopted tests are verified and repaired"
gtaw abort >/dev/null
sed -i 's|    void shouldRejectOrderWhenCustomerIdIsBlank() throws Exception {|&\n        if (Boolean.parseBoolean("true")) { throw new IllegalStateException("broken fixture"); }|' "$TEST"
REPAIRED=0
for i in $(seq 1 30); do
  OUT=$(gtaw next --mode flow --target order.create-order)
  NODE=$(jget "$OUT" 'd.get("node", "result")')
  case "$NODE" in
    author)
      git checkout -q -- "$TEST"
      gtaw submit --item "$(jget "$OUT" 'd["itemId"]')" --token "$(jget "$OUT" 'd["token"]')" >/dev/null
      REPAIRED=$((REPAIRED + 1)) ;;
    review)
      gtaw submit --review "$(jget "$OUT" 'd["itemId"]')" --token "$(jget "$OUT" 'd["token"]')" --verdict accept --reason "unchanged oracle" >/dev/null ;;
    done) break ;;
    result) ;;
    *) fail "unexpected node during rebuild: $OUT" ;;
  esac
done
[ "$REPAIRED" -ge 1 ] || fail "the broken adopted test was never handed to the author"
STATUS=$(gtaw status)
expect "$(jget "$STATUS" '" ".join(sorted(set(i["status"] for i in d["items"])))')" "defect-found passed" "item states after the rebuild"
git diff --quiet || fail "the work tree is dirty after the rebuild"

step "regression: merge, then break the code on a new branch"
git checkout -q main && git merge -q --no-ff feature/gta-tests -m "merge gta tests" && git checkout -q -b feature/regression
sed -i 's/OrderStatus.CREATED));/OrderStatus.PAID));/' src/main/java/com/example/order/service/OrderService.java
git commit -qam "change initial status"
OUT=$(gtaw gate --base main || true)
expect "$(jget "$OUT" 'd["decision"]')" fail "gate after the regression"
jget "$OUT" '" ".join(c["code"] for c in d["failures"])' | grep -q GATE_SCENARIO_REGRESSED || fail "no GATE_SCENARIO_REGRESSED: $OUT"
echo "ok   regression of s01 detected"

step "fix of D1 turns the pinned test red until the annotation is removed"
git checkout -q main && git checkout -q -b fix/threshold
sed -i 's/subtotal.compareTo(threshold) > 0/subtotal.compareTo(threshold) >= 0/' src/main/java/com/example/order/service/PricingService.java
git commit -qam "fix threshold discount"
OUT=$(gtaw gate --base main || true)
expect "$(jget "$OUT" 'd["decision"]')" fail "gate with a stale @KnownDefect"
sed -i '/@KnownDefect(id = "order.create-order\/s03"/d' "$TEST"
git commit -qam "remove known defect pin"
OUT=$(gtaw gate --base main || true)
expect "$(jget "$OUT" 'd["decision"]')" pass "gate after the fix"

printf '\nE2E PASSED (%s)\n' "$WORK"
