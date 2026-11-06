#!/usr/bin/env bash
# Scripted end-to-end run of gtaw against evals/fixtures/order-service (plan §12, P0 exit criteria).
#
# The agentic nodes are played by canned outputs in evals/e2e/scripted-output/ (what a correct analyst/author/reviewer would
# produce), so this checks gtaw's deterministic side: spec lint and rounds, approval commits, planning, the oracle
# lock, the change check, triage, @KnownDefect pinning, verify, the gate and regression detection.
#
# Needs: java 17+, mvn, git, python3; the toolkit installed in ~/.m2 (mvn -B install -DskipTests at the repo root).
# Usage: evals/e2e/run.sh [work-dir]
#   GTA_E2E_MODULE=<name> runs the same loop with the fixture as module <name> of a multi-module repository
#   (build.module, Maven with -pl <name> -am; plan §15 P1).
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
AGENT="$REPO/evals/e2e/scripted-output"
WORK="${1:-$(mktemp -d)}"
export GTA_CLI_JAR="$REPO/toolkit/gta-cli/target/gta-cli.jar"
export GIT_AUTHOR_NAME=gta-e2e GIT_AUTHOR_EMAIL=gta-e2e@example.com GIT_COMMITTER_NAME=gta-e2e GIT_COMMITTER_EMAIL=gta-e2e@example.com
[ -f "$GTA_CLI_JAR" ] || { echo "build the toolkit first: mvn -B install -DskipTests"; exit 2; }

# shellcheck source=lib.sh
source "$REPO/evals/e2e/lib.sh"
MODULE="${GTA_E2E_MODULE:-}"
P="${MODULE:+$MODULE/}"   # the module's folder prefix, empty for the single-module run
TEST=${P}src/test/java/com/example/order/api/OrderControllerIT.java
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
mkdir -p "$WORK/$P" && cp -r "$REPO/evals/fixtures/order-service/." "$WORK/$P"
cd "$WORK"
rm -rf "${P}target"
printf 'target/\n' > .gitignore
if [ -n "$MODULE" ]; then
  cat > pom.xml <<POM
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>orders</artifactId>
  <version>1</version>
  <packaging>pom</packaging>
  <modules><module>$MODULE</module></modules>
</project>
POM
  mkdir -p .gta && printf '{ "build": { "module": "%s" } }\n' "$MODULE" > .gta/config.json
fi
git init -q -b main && git add -A && git commit -qm "order-service fixture"
java -jar "$GTA_CLI_JAR" --root . init --platform copilot >/dev/null 2>&1
git add -A && git commit -qm "gta init"
git checkout -q -b feature/gta-tests

step "a person tells gta what the code cannot say (/gta note)"
gtaw note "Orders are never deleted, only cancelled" >/dev/null
git log -1 --format=%s | grep -q "^gta: note from" || fail "gtaw did not commit the note"
grep -q "never deleted, only cancelled" .gta/notes.md || fail "note not kept in .gta/notes.md"
echo "ok   note kept and committed"

step "analyze: spec R1 with one high-impact question"
OUT=$(gtaw next --mode flow --target order.create-order)
expect "$(jget "$OUT" 'd["node"]')" analyze "first node"
grep -q "never deleted, only cancelled" "$(jget "$OUT" 'd["brief"]')" || fail "the analyst brief lacks the notes"
TOKEN=$(jget "$OUT" 'd["token"]')
mkdir -p .gta/specs/order && cp "$AGENT/create-order.spec.md" .gta/specs/order/create-order.spec.md
OUT=$(gtaw submit --spec order.create-order --token "$TOKEN")
expect "$(jget "$OUT" 'd["rev"]')" R1 "spec revision"
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["type"]')" answer "question round"

step "human takes the recommended answer (1B: the threshold discount is a bug)"
gtaw answer "OK" >/dev/null
grep -q 'id=order.create-order/s03 source=confirmed' .gta/specs/order/create-order.spec.md || fail "s03 not confirmed by gtaw"
grep -q 'B(recommended)' .gta/specs/order/create-order.spec.md || fail "OK should be recorded as taking the recommendation"
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["node"]')" analyze "revision node"
TOKEN=$(jget "$OUT" 'd["token"]')
sed -i 's/discount `0.00` and total `100.00`/discount `10.00` and total `90.00`/' .gta/specs/order/create-order.spec.md
OUT=$(gtaw submit --spec order.create-order --token "$TOKEN")
expect "$(jget "$OUT" 'd["rev"]')" R2 "revised spec"
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["type"]')" approve "approval prompt"
jget "$OUT" 'd["display"]' | grep -q "Needs you" || fail "the approval reaches the human as a status block"
echo "ok   the approval is shown in the status block"

step "human approves (ASCII form)"
gtaw answer "approve order.create-order@R2" >/dev/null
git log -1 --format=%s | grep -q "order.create-order R2 approved" || fail "approval not committed"
echo "ok   approval committed"

step "foundation: seed test"
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["itemId"]')" "order#foundation-01" "first item"
TOKEN=$(jget "$OUT" 'd["token"]')
python3 - "${P}pom.xml" <<'EOF'
import sys
s = open(sys.argv[1]).read()
dep = """    <dependency>
      <groupId>com.gta</groupId>
      <artifactId>gta-junit</artifactId>
      <version>0.1.1-SNAPSHOT</version>
      <scope>test</scope>
    </dependency>
  </dependencies>"""
open(sys.argv[1], "w").write(s.replace("  </dependencies>", dep, 1))
EOF
cp "$AGENT/OrderApplicationIT.java.txt" ${P}src/test/java/com/example/order/OrderApplicationIT.java
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
echo "// out of scope" >> ${P}src/main/java/com/example/order/OrderApplication.java
OUT=$(gtaw submit --item "$ITEM" --token "$TOKEN")
expect "$(jget "$OUT" 'd["code"]')" POLICY_OUT_OF_SCOPE_CHANGE "production edit"
git diff --quiet -- ${P}src/main || fail "production edit was not reverted"
echo "ok   production edit reverted"
TOKEN=$(author s01)
gtaw submit --item "$ITEM" --token "$TOKEN" >/dev/null
review_accept s01
grep -A2 'scenario:order.create-order/s01' "$TEST" | grep -q '@Tag("p[0-2]")' || fail "gtaw did not set the priority tag of s01"
echo "ok   gtaw set the intent and priority tags"

step "s02: the author asks instead of guessing, the person takes the recommendation"
ITEM="order.create-order/s02#component-01"; TOKEN=$(author s02)
OUT=$(gtaw submit --item "$ITEM" --token "$TOKEN" --question "Reuse the seed test's MockMvc setup?" --recommend "yes, same class")
expect "$(jget "$OUT" 'd["type"]')" question "author question"
OUT=$(gtaw next)
jget "$OUT" 'd["display"]' | grep -q "recommended: yes, same class" || fail "the question reaches the person with its recommendation: $OUT"
gtaw answer "OK" >/dev/null
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["itemId"]')" "$ITEM" "author again after the answer"
grep -q "A: yes, same class" "$(jget "$OUT" 'd["brief"]')" || fail "the new brief lacks the answer"
TOKEN=$(jget "$OUT" 'd["token"]')
echo "ok   the answer goes into the author's new brief"
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
grep -q '^- \[reviewer\] .*also assert that nothing was stored' .gta/lessons.md || fail "the reviewer's reject is not a lesson"
git ls-files --error-unmatch .gta/lessons.md >/dev/null 2>&1 || fail ".gta/lessons.md is not committed with the report"
echo "ok   the reviewer's reject is a committed lesson"
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
sed -i 's/OrderStatus.CREATED));/OrderStatus.PAID));/' ${P}src/main/java/com/example/order/service/OrderService.java
git commit -qam "change initial status"
OUT=$(gtaw gate --base main || true)
expect "$(jget "$OUT" 'd["decision"]')" fail "gate after the regression"
jget "$OUT" '" ".join(c["code"] for c in d["failures"])' | grep -q GATE_SCENARIO_REGRESSED || fail "no GATE_SCENARIO_REGRESSED: $OUT"
echo "ok   regression of s01 detected"

step "fix of D1 turns the pinned test red until the annotation is removed"
git checkout -q main && git checkout -q -b fix/threshold
sed -i 's/subtotal.compareTo(threshold) > 0/subtotal.compareTo(threshold) >= 0/' ${P}src/main/java/com/example/order/service/PricingService.java
git commit -qam "fix threshold discount"
OUT=$(gtaw gate --base main || true)
expect "$(jget "$OUT" 'd["decision"]')" fail "gate with a stale @KnownDefect"
sed -i '/@KnownDefect(id = "order.create-order\/s03"/d' "$TEST"
git commit -qam "remove known defect pin"
OUT=$(gtaw gate --base main || true)
expect "$(jget "$OUT" 'd["decision"]')" pass "gate after the fix"

step "mutate: PIT on the changed threshold line, with the developer's unit test"
OUT=$(gtaw mutate --changed --base main)
expect "$(jget "$OUT" '",".join(d["classes"])')" com.example.order.service.PricingService "mutated class"
expect "$(jget "$OUT" 'd["mutants"] > 0 and d["covered"] == d["mutants"]')" True "mutants on the changed line are covered"
jget "$OUT" '" ".join(d["survived"])' | grep -q 'PricingService:.*ConditionalsBoundaryMutator' \
  || fail "the boundary mutant of the fixed line should survive (no test at exactly 100.00): $OUT"
echo "ok   mutate names the surviving boundary mutant ($(jget "$OUT" 'd["strength"]'))"

# P1 capabilities (plan §15): a scheduled job and an application-event listener, each with a seeded defect the
# business-rules document contradicts (D4, D5). Runs on the fixed branch, as a new session.
cap_flow shipping.ship-paid-orders ship-paid-orders.spec.md ShippingJobIT.java.txt \
  "${P}src/test/java/com/example/order/shipping/ShippingJobIT.java" "ShippingJob ships CREATED orders too (D4)"
cap_flow loyalty.on-order-created on-order-created.spec.md LoyaltyListenerIT.java.txt \
  "${P}src/test/java/com/example/order/loyalty/LoyaltyListenerIT.java" "LoyaltyService rounds up (D5)"

# P2 capabilities: two cross-cutting ones that add their skill to a REST flow (security, caching), a Spring Batch job
# and a Flyway migration, each with a seeded defect (D6-D9) the business rules contradict.
step "can-fail: a unit test that passes whatever the code does goes back to the author"
CF="loyalty.on-order-created/s03#unit-01"
UNIT="${P}src/test/java/com/example/order/loyalty/LoyaltyListenerTest.java"
OUT=$(cap_item loyalty.on-order-created/s03 unit)
cp "$AGENT/LoyaltyListenerTest.java.txt" "$UNIT"
gtaw submit --item "$CF" --token "$(jget "$OUT" 'd["token"]')" >/dev/null
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["kind"]')" oracle-review "s03 review"
OUT=$(gtaw submit --review "$CF" --token "$(jget "$OUT" 'd["token"]')" --verdict accept --reason "asserts 3")
jget "$OUT" 'd["result"]' | grep -q '^passed' || fail "s03: $OUT"
OUT=$(gtaw verify --item "$CF")
jget "$OUT" 'd["canFail"]["'"$CF"'"]' | grep -q '^survived' || fail "can-fail should catch the weak test: $OUT"
echo "ok   can-fail: no mutant of the listener made the weak test fail"
OUT=$(cap_item loyalty.on-order-created/s03 unit)
grep -q "POLICY_TEST_CANNOT_FAIL" "$(jget "$OUT" 'd["brief"]')" || fail "the repair brief should say why"
python3 - "$UNIT" <<'PY'
import sys
path = sys.argv[1]
s = open(path).read()
anchor = "        assertThat(expected).isEqualTo(3);\n"
assert anchor in s
open(path, "w").write(s.replace(anchor, anchor + '        assertThat(loyalty.points("c-9")).isEqualTo(3);\n', 1))
PY
OUT=$(gtaw submit --item "$CF" --token "$(jget "$OUT" 'd["token"]')" || true)
for _ in 1 2 3; do
  STATE=$(jget "$(gtaw status)" '[i["status"] for i in d["items"] if i["id"] == "'"$CF"'"][0]')
  [ "$STATE" = passed ] && break
  NEXT=$(gtaw next || true)
  [ "$(jget "$NEXT" 'd.get("kind", "")')" = oracle-review ] || fail "s03 after the repair is $STATE: $OUT / $NEXT"
  gtaw submit --review "$CF" --token "$(jget "$NEXT" 'd["token"]')" --verdict accept --reason "now asserts the points" >/dev/null
done
[ "$STATE" = passed ] || STATE=$(jget "$(gtaw status)" '[i["status"] for i in d["items"] if i["id"] == "'"$CF"'"][0]')
expect "$STATE" passed "s03 after the repair"
OUT=$(gtaw verify --item "$CF")
jget "$OUT" 'd["canFail"]["'"$CF"'"]' | grep -q '^killed' || fail "the repaired test should kill a mutant: $OUT"
echo "ok   can-fail: the repaired test kills the mutant"

cap_flow admin.purge-cancelled purge-cancelled.spec.md AdminControllerIT.java.txt \
  "${P}src/test/java/com/example/order/admin/AdminControllerIT.java" "the admin API only requires a login (D6)" gta-testing-spring-security
cap_flow price.update-price update-price.spec.md PriceControllerIT.java.txt \
  "${P}src/test/java/com/example/order/price/PriceControllerIT.java" "PriceService#update does not evict the cache (D7)" gta-testing-caches
grep -q '^- \[rule-repair\] .*scenario:price.update-price/s01' .gta/lessons.md \
  || fail "the missing scenario tag of price.update-price/s01 should have been repaired by rule"
echo "ok   a missing scenario tag was repaired by rule, before the LLM"
cap_flow archive-job.archive-orders archive-orders.spec.md ArchiveJobConfigIT.java.txt \
  "${P}src/test/java/com/example/order/archive/ArchiveJobConfigIT.java" "the archive step also takes CANCELLED orders (D8)"
cap_flow db.customer-tier customer-tier.spec.md DbCustomerTierIT.java.txt \
  "${P}src/test/java/com/example/order/DbCustomerTierIT.java" "V2 uses > instead of >= (D9)"

step "P1 and P2 session end"
for _ in $(seq 1 5); do
  OUT=$(gtaw next)
  [ "$(jget "$OUT" 'd.get("node")')" = done ] && break
done
expect "$(jget "$OUT" 'd["node"]')" done "P1 and P2 session end"
for f in shipping.ship-paid-orders loyalty.on-order-created admin.purge-cancelled price.update-price \
         archive-job.archive-orders db.customer-tier; do
  STATUS=$(gtaw status)
  expect "$(jget "$STATUS" '" ".join(sorted(set(i["status"] for i in d["items"] if i["id"].startswith("'"$f"'/"))))')" \
    "defect-found passed" "$f item states"
done
git diff --quiet || fail "the work tree is dirty after the P1 and P2 flows"

printf '\nE2E PASSED (%s)\n' "$WORK"
