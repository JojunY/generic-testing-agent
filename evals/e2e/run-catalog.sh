#!/usr/bin/env bash
# Scripted end-to-end run of gtaw against evals/fixtures/catalog-service (plan §15 P3): a WebFlux endpoint, a
# GraphQL query, a gRPC call, a Temporal workflow and a Beam transform, each from spec to a pinned seeded defect
# (D10-D12, D14, D15). The repository opts into P3 with
# `phase` in .gta/config.json, since those capabilities are not on by default yet.
#
# Needs: the same as run.sh. Usage: evals/e2e/run-catalog.sh [work-dir]
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
AGENT="$REPO/evals/e2e/scripted-output/catalog"
WORK="${1:-$(mktemp -d)}"
P=""
export GTA_CLI_JAR="$REPO/toolkit/gta-cli/target/gta-cli.jar"
export GIT_AUTHOR_NAME=gta-e2e GIT_AUTHOR_EMAIL=gta-e2e@example.com GIT_COMMITTER_NAME=gta-e2e GIT_COMMITTER_EMAIL=gta-e2e@example.com
[ -f "$GTA_CLI_JAR" ] || { echo "build the toolkit first: mvn -B install -DskipTests"; exit 2; }
# shellcheck source=lib.sh
source "$REPO/evals/e2e/lib.sh"

step "prepare $WORK"
rm -rf "$WORK" && mkdir -p "$WORK"
cp -r "$REPO/evals/fixtures/catalog-service/." "$WORK/"
cd "$WORK"
rm -rf target
printf 'target/\n' > .gitignore
mkdir -p .gta && printf '{ "phase": "P3" }\n' > .gta/config.json
git init -q -b main && git add -A && git commit -qm "catalog-service fixture"
java -jar "$GTA_CLI_JAR" --root . init --platform copilot >/dev/null 2>&1
git add -A && git commit -qm "gta init"
git checkout -q -b feature/gta-tests

step "product.reserve (webflux): spec and approval"
OUT=$(gtaw next --mode flow --target product.reserve)
expect "$(jget "$OUT" 'd["node"]')" analyze "first node"
TOKEN=$(jget "$OUT" 'd["token"]')
mkdir -p .gta/specs/product && sed "s|__P__||" "$AGENT/reserve.spec.md" > .gta/specs/product/reserve.spec.md
OUT=$(gtaw submit --spec product.reserve --token "$TOKEN")
expect "$(jget "$OUT" 'd["rev"]')" R1 "spec revision"
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["type"]')" approve "approval prompt"
gtaw answer "approve product.reserve@R1" >/dev/null

step "foundation: seed test"
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["itemId"]')" "product#foundation-01" "first item"
grep -q "gta-testing-reactive-apis/SKILL.md" "$(jget "$OUT" 'd["brief"]')" || fail "the foundation brief should load the reactive skill"
echo "ok   briefs load gta-testing-reactive-apis"
TOKEN=$(jget "$OUT" 'd["token"]')
python3 - pom.xml <<'PY'
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
PY
cp "$AGENT/CatalogApplicationIT.java.txt" src/test/java/com/example/catalog/CatalogApplicationIT.java
OUT=$(gtaw submit --item "product#foundation-01" --token "$TOKEN")
jget "$OUT" 'd["result"]' | grep -q '^passed' || fail "foundation: $OUT"
echo "ok   foundation passed"

# cap_flow expects the flow to be unapproved; the reserve flow is approved already, so take its items directly
step "product.reserve: a passing scenario and the seeded defect D10"
OUT=$(cap_item product.reserve/s01)
mkdir -p src/test/java/com/example/catalog/api && cp "$AGENT/ProductControllerIT.java.txt" src/test/java/com/example/catalog/api/ProductControllerIT.java
gtaw submit --item "product.reserve/s01#component-01" --token "$(jget "$OUT" 'd["token"]')" >/dev/null
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["kind"]')" oracle-review "s01 review"
OUT=$(gtaw submit --review "product.reserve/s01#component-01" --token "$(jget "$OUT" 'd["token"]')" --verdict accept --reason "asserts 200 and 3")
jget "$OUT" 'd["result"]' | grep -q '^passed' || fail "s01: $OUT"
OUT=$(cap_item product.reserve/s02)
OUT=$(gtaw submit --item "product.reserve/s02#component-01" --token "$(jget "$OUT" 'd["token"]')")
expect "$(jget "$OUT" 'd["code"]')" TEST_ASSERTION_FAILED "s02 exposes D10"
OUT=$(gtaw next)
gtaw submit --review "product.reserve/s02#component-01" --token "$(jget "$OUT" 'd["token"]')" --verdict product-defect --reason "no stock check (D10)" >/dev/null
OUT=$(gtaw next)
expect "$(jget "$OUT" 'd["type"]')" defect "defect decision"
gtaw answer A >/dev/null
OUT=$(cap_item product.reserve/s02)
TOKEN=$(jget "$OUT" 'd["token"]')
FP=$(grep -o 'failure = "[0-9a-f]*"' "$(jget "$OUT" 'd["brief"]')" | head -1 | grep -o '[0-9a-f]\{12\}')
sed -i "s|    @DisplayName(\"\[product.reserve/s02\]|    @KnownDefect(id = \"product.reserve/s02\", failure = \"$FP\")\n&|" src/test/java/com/example/catalog/api/ProductControllerIT.java
OUT=$(gtaw submit --item "product.reserve/s02#component-01" --token "$TOKEN")
jget "$OUT" 'd["result"]' | grep -q '^defect-found' || fail "s02 pin: $OUT"
echo "ok   product.reserve/s02 pinned with @KnownDefect ($FP)"

cap_flow product-graphql.products-up-to products-up-to.spec.md ProductGraphqlControllerIT.java.txt \
  src/test/java/com/example/catalog/graphql/ProductGraphqlControllerIT.java "< instead of <= (D11)" gta-testing-spring-apis

cap_flow stock.get-stock get-stock.spec.md StockGrpcServiceIT.java.txt \
  src/test/java/com/example/catalog/grpc/StockGrpcServiceIT.java ">= instead of > (D12)" gta-testing-spring-apis

cap_flow restock.restock restock.spec.md RestockWorkflowImplTest.java.txt \
  src/test/java/com/example/catalog/restock/RestockWorkflowImplTest.java "no compensation on a failed stock update (D14)" \
  gta-testing-workflows unit

cap_flow low-stock.transform low-stock.spec.md LowStockTransformTest.java.txt \
  src/test/java/com/example/catalog/report/LowStockTransformTest.java "2 left is not reported (D15)" \
  gta-testing-beam-pipelines unit

step "session end"
for _ in $(seq 1 5); do
  OUT=$(gtaw next)
  [ "$(jget "$OUT" 'd.get("node")')" = done ] && break
done
expect "$(jget "$OUT" 'd["node"]')" done "session end"
for f in product.reserve product-graphql.products-up-to stock.get-stock restock.restock low-stock.transform; do
  STATUS=$(gtaw status)
  expect "$(jget "$STATUS" '" ".join(sorted(set(i["status"] for i in d["items"] if i["id"].startswith("'"$f"'/"))))')" \
    "defect-found passed" "$f item states"
done
git diff --quiet || fail "the work tree is dirty"

printf '\nE2E PASSED (%s)\n' "$WORK"
