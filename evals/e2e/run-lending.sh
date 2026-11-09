#!/usr/bin/env bash
# Scripted end-to-end run of gtaw against evals/fixtures/lending-service (plan §7.10, §15 P4): a repository that already
# runs Cucumber. A REST flow behind a Resilience4j circuit breaker, a Spring Cloud Stream function and a Spring
# Integration flow are each written as a feature file with step definitions, from spec to a pinned seeded defect
# (D19-D21); the pins are Cucumber tags. Secret Manager and the Pub/Sub binder are faked by the seed test, never called.
#
# Needs: the same as run.sh. Usage: evals/e2e/run-lending.sh [work-dir]
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
AGENT="$REPO/evals/e2e/scripted-output/lending"
WORK="${1:-$(mktemp -d)}"
P=""
export GTA_CLI_JAR="$REPO/toolkit/gta-cli/target/gta-cli.jar"
export GIT_AUTHOR_NAME=gta-e2e GIT_AUTHOR_EMAIL=gta-e2e@example.com GIT_COMMITTER_NAME=gta-e2e GIT_COMMITTER_EMAIL=gta-e2e@example.com
[ -f "$GTA_CLI_JAR" ] || { echo "build the toolkit first: mvn -B install -DskipTests"; exit 2; }
# shellcheck source=lib.sh
source "$REPO/evals/e2e/lib.sh"

step "prepare $WORK"
rm -rf "$WORK" && mkdir -p "$WORK"
cp -r "$REPO/evals/fixtures/lending-service/." "$WORK/"
cd "$WORK"
rm -rf target
printf 'target/\n' > .gitignore
git init -q -b main && git add -A && git commit -qm "lending-service fixture"
gta_init copilot
git add -A && git commit -qm "gta init"
git checkout -q -b feature/gta-tests

check_profile() { # the capabilities, flows and Cucumber suite gtaw found
  local profile c
  profile=$(cat .gta/work/service-profile.json)
  for c in rest-api messaging integration resilience secret-manager cucumber; do
    jget "$profile" '"'"$c"'" in d["capabilities"]' | grep -q True || fail "capability $c not detected"
  done
  echo "ok   rest-api, messaging, integration, resilience, secret-manager and cucumber detected"
  expect "$(jget "$profile" 'd["cucumber"]["suite"]')" com.example.lending.RunCucumberTest "Cucumber suite"
  expect "$(jget "$profile" '" ".join(sorted(e["flowId"] for e in d["entrypoints"]))')" \
    "borrow.book fee-accrual.flow recall.recall" "flows"
}

# feature_flow <flowId> <spec> <feature template> <feature path> <steps template> <steps path> <triage reason> [skill]
feature_flow() {
  local flow=$1 spec=$2 feature=$3 fpath=$4 steps=$5 spath=$6 reason=$7 skill=${8:-} out token fp brief
  local domain=${1%%.*} name=${1#*.}
  step "$flow: spec, approval, a passing scenario and a seeded defect pinned with Cucumber tags"
  out=$(gtaw next --mode flow --target "$flow")
  expect "$(jget "$out" 'd["node"]')" analyze "$flow first node"
  token=$(jget "$out" 'd["token"]')
  [ "$domain" = borrow ] && check_profile
  mkdir -p ".gta/specs/$domain" && sed "s|__P__|$P|" "$AGENT/$spec" > ".gta/specs/$domain/$name.spec.md"
  out=$(gtaw submit --spec "$flow" --token "$token")
  expect "$(jget "$out" 'd["rev"]')" R1 "$flow spec revision"
  out=$(gtaw next)
  expect "$(jget "$out" 'd["type"]')" approve "$flow approval prompt"
  gtaw answer OK >/dev/null

  if [ "$domain" = borrow ]; then
    out=$(gtaw next)
    expect "$(jget "$out" 'd["itemId"]')" "borrow#foundation-01" "seed item"
    brief=$(jget "$out" 'd["brief"]')
    grep -q "KnownDefects.abortPinnedOutsideGta" "$brief" || fail "the seed brief should ask for the Cucumber hook"
    echo "ok   the seed brief asks for the pin hook"
    add_gta_junit pom.xml
    mkdir -p src/test/java/com/example/lending/support src/test/resources/META-INF src/test/resources/config
    cp "$AGENT/LendingApplicationIT.java.txt" src/test/java/com/example/lending/LendingApplicationIT.java
    cp "$AGENT/FakeSecretManager.java.txt" src/test/java/com/example/lending/support/FakeSecretManager.java
    cp "$AGENT/GtaCucumberHooks.java.txt" src/test/java/com/example/lending/GtaCucumberHooks.java
    cp "$AGENT/spring.factories.txt" src/test/resources/META-INF/spring.factories
    cp "$AGENT/config-application.properties.txt" src/test/resources/config/application.properties
    out=$(gtaw submit --item "borrow#foundation-01" --token "$(jget "$out" 'd["token"]')")
    jget "$out" 'd["result"]' | grep -q '^passed' || fail "seed: $out"
    echo "ok   seed passed with a fake Secret Manager and the Cloud Stream test binder"
  fi

  out=$(cap_item "$flow/s01")
  brief=$(jget "$out" 'd["brief"]')
  grep -q "Feature: \`$fpath\`" "$brief" || fail "$flow brief does not name the feature file $fpath"
  grep -q "gta-writing-cucumber-scenarios/SKILL.md" "$brief" || fail "$flow brief does not load the Cucumber skill"
  if [ -n "$skill" ]; then
    grep -q "$skill/SKILL.md" "$brief" || fail "$flow brief does not load $skill"
  fi
  echo "ok   $flow brief names $fpath and loads the Cucumber skill${skill:+ and $skill}"
  mkdir -p "$(dirname "$fpath")" "$(dirname "$spath")"
  cp "$AGENT/$feature" "$fpath"
  cp "$AGENT/$steps" "$spath"
  gtaw submit --item "$flow/s01#component-01" --token "$(jget "$out" 'd["token"]')" >/dev/null
  out=$(gtaw next)
  expect "$(jget "$out" 'd["kind"]')" oracle-review "$flow/s01 review"
  out=$(gtaw submit --review "$flow/s01#component-01" --token "$(jget "$out" 'd["token"]')" --verdict accept --reason "the Then steps assert the THEN values")
  jget "$out" 'd["result"]' | grep -q '^passed' || fail "$flow/s01: $out"
  echo "ok   $flow/s01 passed and committed"

  out=$(cap_item "$flow/s02")
  out=$(gtaw submit --item "$flow/s02#component-01" --token "$(jget "$out" 'd["token"]')")
  expect "$(jget "$out" 'd["code"]')" TEST_ASSERTION_FAILED "$flow/s02 exposes the seeded defect"
  out=$(gtaw next)
  expect "$(jget "$out" 'd["kind"]')" triage "$flow/s02 triage"
  gtaw submit --review "$flow/s02#component-01" --token "$(jget "$out" 'd["token"]')" --verdict product-defect --reason "$reason" >/dev/null
  out=$(gtaw next)
  expect "$(jget "$out" 'd["type"]')" defect "$flow defect decision"
  gtaw answer OK >/dev/null
  out=$(cap_item "$flow/s02")
  token=$(jget "$out" 'd["token"]')
  fp=$(grep -o 'failure = "[0-9a-f]*"' "$(jget "$out" 'd["brief"]')" | head -1 | grep -o '[0-9a-f]\{12\}')
  grep -q "@known-defect:$fp" "$(jget "$out" 'd["brief"]')" || fail "$flow pin brief does not give the tags"
  sed -i "s|^\(  @scenario:$flow/s02 .*\)$|\1 @known-defect @known-defect:$fp|" "$fpath"
  grep -q "@known-defect:$fp" "$fpath" || fail "$flow: the pin tags were not added"
  out=$(gtaw submit --item "$flow/s02#component-01" --token "$token")
  jget "$out" 'd["result"]' | grep -q '^defect-found' || fail "$flow/s02 pin: $out"
  echo "ok   $flow/s02 pinned with @known-defect:$fp"
}

feature_flow borrow.book book.spec.md book.feature.txt src/test/resources/features/borrow/book.feature \
  BorrowControllerSteps.java.txt src/test/java/com/example/lending/borrow/BorrowControllerSteps.java \
  "an unreachable inventory books the borrow (D19)" gta-testing-resilience

feature_flow recall.recall recall.spec.md recall.feature.txt src/test/resources/features/recall/recall.feature \
  RecallFunctionsSteps.java.txt src/test/java/com/example/lending/recall/RecallFunctionsSteps.java \
  "a recall above the borrowed quantity is not capped (D20)" gta-testing-event-flows

feature_flow fee-accrual.flow fee-accrual.spec.md fee-accrual.feature.txt src/test/resources/features/fee-accrual/flow.feature \
  FeeAccrualIntegrationConfigSteps.java.txt src/test/java/com/example/lending/accrual/FeeAccrualIntegrationConfigSteps.java \
  "the daily fee divides by 365 (D21)" gta-testing-integration-flows

step "a plain build stays green: pinned scenarios are aborted by the hook"
mvn -B -q test -Dtest=RunCucumberTest >/dev/null 2>&1 || fail "mvn test is red with the pinned scenarios"
SKIPPED=$(grep -o 'skipped="[0-9]*"' target/surefire-reports/TEST-com.example.lending.RunCucumberTest.xml | head -1 | grep -o '[0-9]*')
expect "$SKIPPED" 3 "pinned scenarios a plain build reports as aborted"

step "session end"
for _ in $(seq 1 5); do
  OUT=$(gtaw next)
  [ "$(jget "$OUT" 'd.get("node")')" = done ] && break
done
expect "$(jget "$OUT" 'd["node"]')" done "session end"
for f in borrow.book recall.recall fee-accrual.flow; do
  STATUS=$(gtaw status)
  expect "$(jget "$STATUS" '" ".join(sorted(set(i["status"] for i in d["items"] if i["id"].startswith("'"$f"'/"))))')" \
    "defect-found passed" "$f item states"
done
rm -rf target
git diff --quiet || fail "the work tree is dirty"

printf '\nE2E PASSED (%s)\n' "$WORK"
