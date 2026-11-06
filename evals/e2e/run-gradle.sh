#!/usr/bin/env bash
# Scripted end-to-end run of gtaw against evals/fixtures/invoice-service (plan §15 P3), a Spring MVC service built with
# Gradle (Kotlin DSL): gtaw reads build.gradle.kts, runs the tests through Gradle with its init script, and takes one
# REST flow from spec to the pinned seeded defect D13.
#
# Needs: the same as run.sh, plus Gradle 8.14 or newer on PATH (the fixture has no wrapper), and gta-junit in the local
# Maven repository (mvn install). Usage: evals/e2e/run-gradle.sh [work-dir]
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
AGENT="$REPO/evals/e2e/scripted-output/invoice"
WORK="${1:-$(mktemp -d)}"
P=""
export GTA_CLI_JAR="$REPO/toolkit/gta-cli/target/gta-cli.jar"
export GIT_AUTHOR_NAME=gta-e2e GIT_AUTHOR_EMAIL=gta-e2e@example.com GIT_COMMITTER_NAME=gta-e2e GIT_COMMITTER_EMAIL=gta-e2e@example.com
[ -f "$GTA_CLI_JAR" ] || { echo "build the toolkit first: mvn -B install -DskipTests"; exit 2; }
command -v gradle >/dev/null || { echo "install Gradle 8.14 or newer"; exit 2; }
# shellcheck source=lib.sh
source "$REPO/evals/e2e/lib.sh"

step "prepare $WORK"
rm -rf "$WORK" && mkdir -p "$WORK"
cp -r "$REPO/evals/fixtures/invoice-service/." "$WORK/"
cd "$WORK"
rm -rf build .gradle
git init -q -b main && git add -A && git commit -qm "invoice-service fixture"
gta_init opencode
git add -A && git commit -qm "gta init"
git checkout -q -b feature/gta-tests

step "doctor sees the Gradle build"
OUT=$(gtaw doctor || true)
expect "$(jget "$OUT" '[c["status"] for c in d["checks"] if c["check"] == "gradle"][0]')" ok "gradle check"
expect "$(jget "$OUT" '[c["detail"] for c in d["checks"] if c["check"] == "spring-boot"][0]')" "Spring Boot 4.1.2 → JUnit 6" "Boot version from build.gradle.kts"

step "invoice.quote: spec and approval"
OUT=$(gtaw next --mode flow --target invoice.quote)
expect "$(jget "$OUT" 'd["node"]')" analyze "first node"
TOKEN=$(jget "$OUT" 'd["token"]')
mkdir -p .gta/specs/invoice && sed "s|__P__||" "$AGENT/quote.spec.md" > .gta/specs/invoice/quote.spec.md
OUT=$(gtaw submit --spec invoice.quote --token "$TOKEN")
expect "$(jget "$OUT" 'd["rev"]')" R1 "spec revision"
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["type"]')" approve "approval prompt"
gtaw answer "approve invoice.quote@R1" >/dev/null

step "foundation: seed test, gta-junit added to build.gradle.kts"
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["itemId"]')" "invoice#foundation-01" "first item"
TOKEN=$(jget "$OUT" 'd["token"]')
sed -i 's|^    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")|&\n    testImplementation("com.gta:gta-junit:0.1.1-SNAPSHOT")|' build.gradle.kts
grep -q gta-junit build.gradle.kts || fail "gta-junit not added"
cp "$AGENT/InvoiceApplicationIT.java.txt" src/test/java/com/example/invoice/InvoiceApplicationIT.java
OUT=$(gtaw submit --item "invoice#foundation-01" --token "$TOKEN")
jget "$OUT" 'd["result"]' | grep -q '^passed' || fail "foundation: $OUT"
echo "ok   foundation passed through Gradle"
[ -f .gta/work/gradle/gta-init.gradle ] || fail "no gtaw init script"
git diff --quiet build.gradle.kts || fail "build.gradle.kts should be committed with the foundation"

step "invoice.quote: a passing scenario and the seeded defect D13"
OUT=$(cap_item invoice.quote/s01)
mkdir -p src/test/java/com/example/invoice/api && cp "$AGENT/InvoiceControllerIT.java.txt" src/test/java/com/example/invoice/api/InvoiceControllerIT.java
gtaw submit --item "invoice.quote/s01#component-01" --token "$(jget "$OUT" 'd["token"]')" >/dev/null
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["kind"]')" oracle-review "s01 review"
OUT=$(gtaw submit --review "invoice.quote/s01#component-01" --token "$(jget "$OUT" 'd["token"]')" --verdict accept --reason "asserts 200 and 40.00")
jget "$OUT" 'd["result"]' | grep -q '^passed' || fail "s01: $OUT"
echo "ok   invoice.quote/s01 passed and committed"
OUT=$(cap_item invoice.quote/s02)
OUT=$(gtaw submit --item "invoice.quote/s02#component-01" --token "$(jget "$OUT" 'd["token"]')")
expect "$(jget "$OUT" 'd["code"]')" TEST_ASSERTION_FAILED "s02 exposes D13"
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["kind"]')" triage "s02 triage"
gtaw submit --review "invoice.quote/s02#component-01" --token "$(jget "$OUT" 'd["token"]')" --verdict product-defect --reason "> instead of >= (D13)" >/dev/null
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["type"]')" defect "defect decision"
gtaw answer A >/dev/null
OUT=$(cap_item invoice.quote/s02)
TOKEN=$(jget "$OUT" 'd["token"]')
FP=$(grep -o 'failure = "[0-9a-f]*"' "$(jget "$OUT" 'd["brief"]')" | head -1 | grep -o '[0-9a-f]\{12\}')
sed -i "s|    @DisplayName(\"\[invoice.quote/s02\]|    @KnownDefect(id = \"invoice.quote/s02\", failure = \"$FP\")\n&|" src/test/java/com/example/invoice/api/InvoiceControllerIT.java
OUT=$(gtaw submit --item "invoice.quote/s02#component-01" --token "$TOKEN")
jget "$OUT" 'd["result"]' | grep -q '^defect-found' || fail "s02 pin: $OUT"
echo "ok   invoice.quote/s02 pinned with @KnownDefect ($FP)"

step "session end"
for _ in $(seq 1 5); do
  OUT=$(gtaw next)
  [ "$(jget "$OUT" 'd.get("node")')" = done ] && break
done
expect "$(jget "$OUT" 'd["node"]')" done "session end"
STATUS=$(gtaw status)
expect "$(jget "$STATUS" '" ".join(sorted(set(i["status"] for i in d["items"] if i["id"].startswith("invoice.quote/"))))')" \
  "defect-found passed" "invoice.quote item states"
git diff --quiet || fail "the work tree is dirty"

step "mutate on a Gradle build: PIT through gtaw's init script, the build script untouched"
CTRL=src/main/java/com/example/invoice/api/InvoiceController.java
UNIT=src/test/java/com/example/invoice/api/InvoiceControllerTest.java
cat > "$UNIT" <<'JAVA'
package com.example.invoice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class InvoiceControllerTest {
    @Test
    void shouldDiscountALargeInvoice() {
        assertThat(new InvoiceController().quote(new BigDecimal("200")).total()).isEqualByComparingTo("180.00");
    }
}
JAVA
sed -i 's/compareTo(DISCOUNT_FROM) > 0/compareTo(DISCOUNT_FROM) >= 0/' "$CTRL"
OUT=$(gtaw mutate --changed --base HEAD)
[ "$(jget "$OUT" 'd["killed"]')" -ge 1 ] || fail "PIT should kill a mutant of the changed line: $OUT"
git diff --quiet -- build.gradle.kts || fail "gtaw changed the build script"
echo "ok   PIT killed $(jget "$OUT" 'd["killed"]') of $(jget "$OUT" 'd["mutants"]') mutants on the changed line"
git checkout -q -- "$CTRL" && rm "$UNIT"

step "incident mode: a reported overcharge becomes a reproducing test"
OUT=$(gtaw next --mode incident --target invoice.quote \
  --intent "A customer was charged 100.00 for an invoice of 100.00. Our terms say invoices of 100 or more get 10 % off.")
expect "$(jget "$OUT" 'd["node"]')" analyze "incident node"
grep -q "hint.intent=reproducing" "$(jget "$OUT" 'd["brief"]')" || fail "the analyst brief should ask for a reproducing scenario"
grep -q "invoices of 100 or more get 10 % off" .gta/specs/invoice/quote.incidents.md || fail "the report should be kept next to the spec"
TOKEN=$(jget "$OUT" 'd["token"]')
python3 - .gta/specs/invoice/quote.spec.md "$AGENT/s03-incident.scenario.md" <<'PY'
import sys
spec, scenario = sys.argv[1:]
s = open(spec).read()
open(spec, "w").write(s.replace("## Invariants", open(scenario).read() + "## Invariants", 1))
PY
OUT=$(gtaw submit --spec invoice.quote --token "$TOKEN")
expect "$(jget "$OUT" 'd["rev"]')" R2 "incident spec revision"
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["type"]')" approve "the human confirms the expectation"
gtaw answer "approve invoice.quote@R2" >/dev/null
OUT=$(cap_item invoice.quote/s03)
grep -q "Reproducing an incident" "$(jget "$OUT" 'd["brief"]')" || fail "the author brief should say the test must fail"
python3 - src/test/java/com/example/invoice/api/InvoiceControllerIT.java "$AGENT/s03.method.java.txt" <<'PY'
import sys
path, method = sys.argv[1:]
s = open(path).read().rstrip()
open(path, "w").write(s[: s.rindex("}")] + open(method).read())
PY
OUT=$(gtaw submit --item "invoice.quote/s03#component-01" --token "$(jget "$OUT" 'd["token"]')")
jget "$OUT" 'd["result"]' | grep -q '^needs-human' || fail "a reproducing test that fails goes to the human: $OUT"
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["type"]')" defect "defect confirmation"
gtaw answer A >/dev/null
OUT=$(cap_item invoice.quote/s03)
TOKEN=$(jget "$OUT" 'd["token"]')
FP=$(grep -o 'failure = "[0-9a-f]*"' "$(jget "$OUT" 'd["brief"]')" | head -1 | grep -o '[0-9a-f]\{12\}')
sed -i "s|    @DisplayName(\"\[invoice.quote/s03\]|    @KnownDefect(id = \"invoice.quote/s03\", failure = \"$FP\")\n&|" src/test/java/com/example/invoice/api/InvoiceControllerIT.java
OUT=$(gtaw submit --item "invoice.quote/s03#component-01" --token "$TOKEN")
jget "$OUT" 'd["result"]' | grep -q '^defect-found' || fail "s03 pin: $OUT"
echo "ok   invoice.quote/s03 reproduces the incident and is pinned ($FP)"
for _ in $(seq 1 5); do
  OUT=$(gtaw next)
  [ "$(jget "$OUT" 'd.get("node")')" = done ] && break
done
git diff --quiet || fail "the work tree is dirty after the incident"

step "PR mode: the branch changes the quote and adds a late fee; a catching test catches its bug (D16)"
sed -i 's|new BigDecimal("0.90")|new BigDecimal("0.85")|' src/main/java/com/example/invoice/api/InvoiceController.java
python3 - src/main/java/com/example/invoice/api/InvoiceController.java "$AGENT/late-fee.method.java.txt" <<'PY'
import sys
path, method = sys.argv[1:]
s = open(path).read().rstrip()
open(path, "w").write(s[: s.rindex("}")] + open(method).read())
PY
git commit -qam "Raise the discount to 15 % and add a late fee"
OUT=$(gtaw next --mode pr --base main \
  --intent "Invoices of 100 or more now get 15 % off instead of 10 %. Adds a late fee of 5.00 for invoices paid after their due day.")
expect "$(jget "$OUT" 'd["node"]')" analyze "PR mode node"
expect "$(jget "$OUT" 'd["flowId"]')" invoice.quote "first affected flow"
expect "$(jget "$OUT" '" ".join(d["pr"]["flows"])')" "invoice.quote invoice.late-fee" "PR flows (stale spec, new entry point)"
grep -q "status=stale" .gta/specs/invoice/quote.spec.md || fail "the approved spec should be stale"
grep -q "15 % off instead of 10 %" "$(jget "$OUT" 'd["brief"]')" || fail "the analyst brief should quote the PR description"
echo "ok   the analyst brief quotes the PR description"
OUT=$(gtaw submit --spec invoice.quote --token "$(jget "$OUT" 'd["token"]')")
REV=$(jget "$OUT" 'd["rev"]')
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["type"]')" approve "quote re-approval"
gtaw answer "approve invoice.quote@$REV" >/dev/null
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["node"]')" analyze "the next PR flow"
expect "$(jget "$OUT" 'd["flowId"]')" invoice.late-fee "the new entry point"
cp "$AGENT/late-fee.spec.md" .gta/specs/invoice/late-fee.spec.md
OUT=$(gtaw submit --spec invoice.late-fee --token "$(jget "$OUT" 'd["token"]')")
expect "$(jget "$OUT" 'd["rev"]')" R1 "late-fee spec revision"
OUT=$(gtaw next)
gtaw answer "approve invoice.late-fee@R1" >/dev/null
OUT=$(cap_item invoice.late-fee/s01)
python3 - src/test/java/com/example/invoice/api/InvoiceControllerIT.java "$AGENT/late-fee-tests.method.java.txt" <<'PY'
import sys
path, method = sys.argv[1:]
s = open(path).read().rstrip()
open(path, "w").write(s[: s.rindex("}")] + open(method).read())
PY
gtaw submit --item "invoice.late-fee/s01#component-01" --token "$(jget "$OUT" 'd["token"]')" >/dev/null
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["kind"]')" oracle-review "late-fee s01 review"
OUT=$(gtaw submit --review "invoice.late-fee/s01#component-01" --token "$(jget "$OUT" 'd["token"]')" --verdict accept --reason "asserts 200 and 5.00")
jget "$OUT" 'd["result"]' | grep -q '^passed' || fail "late-fee s01: $OUT"
OUT=$(cap_item invoice.late-fee/s02)
OUT=$(gtaw submit --item "invoice.late-fee/s02#component-01" --token "$(jget "$OUT" 'd["token"]')")
expect "$(jget "$OUT" 'd["code"]')" TEST_ASSERTION_FAILED "the catching test fails on the branch"
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["kind"]')" triage "catching assessment"
BRIEF="$(jget "$OUT" 'd["brief"]')"
grep -q "Catching test (assess it)" "$BRIEF" || fail "the assessor brief should say it is a catching test"
grep -q "new on this branch" "$BRIEF" || fail "the base check should see the late fee is new"
echo "ok   the assessor brief has the base check and the PR description"
gtaw submit --review "invoice.late-fee/s02#component-01" --token "$(jget "$OUT" 'd["token"]')" --verdict product-defect --reason "charged on the due day (D16)" >/dev/null
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["type"]')" defect "the human confirms the catch"
gtaw answer A >/dev/null
OUT=$(cap_item invoice.late-fee/s02)
TOKEN=$(jget "$OUT" 'd["token"]')
FP=$(grep -o 'failure = "[0-9a-f]*"' "$(jget "$OUT" 'd["brief"]')" | head -1 | grep -o '[0-9a-f]\{12\}')
sed -i "s|    @DisplayName(\"\[invoice.late-fee/s02\]|    @KnownDefect(id = \"invoice.late-fee/s02\", failure = \"$FP\")\n&|" src/test/java/com/example/invoice/api/InvoiceControllerIT.java
OUT=$(gtaw submit --item "invoice.late-fee/s02#component-01" --token "$TOKEN")
jget "$OUT" 'd["result"]' | grep -q '^defect-found' || fail "late-fee s02 pin: $OUT"
for _ in $(seq 1 5); do
  OUT=$(gtaw next)
  [ "$(jget "$OUT" 'd.get("node")')" = done ] && break
done
expect "$(jget "$OUT" 'd["node"]')" done "PR session end"
grep -rq "Catching hits: 1 of 1" .gta/reports || fail "the report should count the catching hit"
echo "ok   the report counts 1 catching hit"
git diff --quiet || fail "the work tree is dirty after the PR session"

printf '\nE2E PASSED (%s)\n' "$WORK"
