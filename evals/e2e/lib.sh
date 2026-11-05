# Helpers shared by the scripted e2e runs (run.sh, run-catalog.sh). Expects REPO, AGENT, WORK and P to be set.
# shellcheck shell=bash
step() { printf '\n== %s\n' "$*"; }
fail() { echo "E2E FAILED: $*" >&2; exit 1; }
gtaw() { ./gtaw "$@" 2>/dev/null; }
# jget <json> <python expression on d>; the expressions are literals of this script, never input
jget() { python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(eval(sys.argv[2]))' "$1" "$2"; }
expect() { # expect <actual> <expected> <what>
  [ "$1" = "$2" ] || fail "$3: expected '$2', got '$1'"
  echo "ok   $3 = $2"
}
MODULE="${GTA_E2E_MODULE:-}"
P="${MODULE:+$MODULE/}"   # the module's folder prefix, empty for the single-module run

cap_item() { # cap_item <scenarioId> [layer] -> the author node of that scenario (the domain's seed is adopted on the way)
  local out node layer=${2:-component}
  for _ in $(seq 1 10); do
    out=$(gtaw next)
    node=$(jget "$out" 'd.get("node", "result")')
    if [ "$node" = author ] && [ "$(jget "$out" 'd.get("itemId")')" = "$1#$layer-01" ]; then echo "$out"; return; fi
    case "$node" in done|result) ;; *) fail "unexpected node before $1: $out" ;; esac
  done
  fail "no author node for $1"
}
cap_flow() { # cap_flow <flowId> <spec> <test template> <test path> <triage reason> [skill the briefs must load]
  local flow=$1 spec=$2 tmpl=$3 path=$4 reason=$5 skill=${6:-} out token fp
  local domain=${1%%.*} name=${1#*.}
  step "$flow: spec, approval, a passing scenario and a seeded defect"
  out=$(gtaw next --mode flow --target "$flow")
  expect "$(jget "$out" 'd["node"]')" analyze "$flow first node"
  token=$(jget "$out" 'd["token"]')
  mkdir -p ".gta/specs/$domain" && sed "s|__P__|$P|" "$AGENT/$spec" > ".gta/specs/$domain/$name.spec.md"
  out=$(gtaw submit --spec "$flow" --token "$token")
  expect "$(jget "$out" 'd["rev"]')" R1 "$flow spec revision"
  out=$(gtaw next)
  expect "$(jget "$out" 'd["type"]')" approve "$flow approval prompt"
  gtaw answer "approve $flow@R1" >/dev/null

  out=$(cap_item "$flow/s01")
  if [ -n "$skill" ]; then
    grep -q "$skill/SKILL.md" "$(jget "$out" 'd["brief"]')" || fail "$flow brief does not load $skill"
    echo "ok   $flow brief loads $skill"
  fi
  mkdir -p "$(dirname "$path")" && cp "$AGENT/$tmpl" "$path"
  gtaw submit --item "$flow/s01#component-01" --token "$(jget "$out" 'd["token"]')" >/dev/null
  out=$(gtaw next)
  expect "$(jget "$out" 'd["kind"]')" oracle-review "$flow/s01 review"
  out=$(gtaw submit --review "$flow/s01#component-01" --token "$(jget "$out" 'd["token"]')" --verdict accept --reason "asserts the THEN values")
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
  gtaw answer A >/dev/null
  out=$(cap_item "$flow/s02")   # the session may end on its item budget first
  token=$(jget "$out" 'd["token"]')
  fp=$(grep -o 'failure = "[0-9a-f]*"' "$(jget "$out" 'd["brief"]')" | head -1 | grep -o '[0-9a-f]\{12\}')
  python3 - "$path" "$flow/s02" "$fp" <<'PY'
import sys
path, scenario, fp = sys.argv[1:]
s = open(path).read()
marker = '    @DisplayName("[' + scenario + ']'
assert marker in s, marker
s = s.replace(marker, '    @KnownDefect(id = "' + scenario + '", failure = "' + fp + '")\n' + marker, 1)
open(path, "w").write(s)
PY
  out=$(gtaw submit --item "$flow/s02#component-01" --token "$token")
  jget "$out" 'd["result"]' | grep -q '^defect-found' || fail "$flow/s02 pin: $out"
  echo "ok   $flow/s02 pinned with @KnownDefect ($fp)"
}
