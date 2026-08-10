#!/bin/bash
# Phase 7-9 acceptance harness for OMS hot-path validation.
#
# Measures:
# - shadow replay pass rate from the append-only input log
# - submit latency under a chosen load profile
# - controlled degradation via kill-switch rejection
# - optional JFR capture for allocation/GC inspection
#
# Usage:
#   scripts/perf/hotpath-acceptance.sh
#   PROFILE=1 RATE=40 DURATION=90s scripts/perf/hotpath-acceptance.sh
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

OMS_URL="${OMS_URL:-http://localhost:8086}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8082}"
ORIGIN="${EMPORIA_ORIGIN:-http://localhost:3001}"
RATE="${RATE:-20}"
DURATION="${DURATION:-60s}"
DESTINATION="${DESTINATION:-DMA}"
LISTING_IDS="${LISTING_IDS:-1}"
QUANTITY="${QUANTITY:-10}"
LIMIT_PRICE="${LIMIT_PRICE:-100.00}"
SHADOW_LIMIT="${SHADOW_LIMIT:-200}"
REQUIRED_SHADOW_PASS_RATE="${REQUIRED_SHADOW_PASS_RATE:-1.0}"
MAX_P99_MS="${MAX_P99_MS:-0}"
PROFILE="${PROFILE:-0}"
OUT_DIR="${HOTPATH_ACCEPTANCE_OUT_DIR:-$repo_root/.local-run/hotpath-acceptance/$(date +%Y%m%d-%H%M%S)}"

mkdir -p "$OUT_DIR"

fail() { echo "FAIL: $*" >&2; exit 1; }

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

require_cmd curl
require_cmd node
require_cmd python3
require_cmd k6

ACCESS_TOKEN="$({ EMPORIA_ORIGIN="$ORIGIN" EMPORIA_USERNAME="${EMPORIA_USERNAME:-admin}" EMPORIA_PASSWORD="${EMPORIA_PASSWORD:-admin123}" node "$repo_root/scripts/perf/get-access-token.mjs"; })"

auth_header=(-H "Authorization: Bearer ${ACCESS_TOKEN}")

status_json() {
    curl -fsS "${auth_header[@]}" "$OMS_URL/internal/hotpath/status"
}

shadow_json() {
    local query="limit=$SHADOW_LIMIT"
    if [ -n "${SHADOW_AFTER_SEQUENCE_ID:-}" ]; then
        query="${query}&afterSequenceId=${SHADOW_AFTER_SEQUENCE_ID}"
    fi
    curl -fsS "${auth_header[@]}" "$OMS_URL/internal/hotpath/shadow-report?${query}"
}

engage_kill_switch() {
    curl -fsS -X POST "${auth_header[@]}" "$OMS_URL/internal/hotpath/kill-switch?reason=acceptance-drill" >/dev/null
}

release_kill_switch() {
    curl -fsS -X DELETE "${auth_header[@]}" "$OMS_URL/internal/hotpath/kill-switch" >/dev/null
}

submit_one_order() {
    curl -sS -o "$OUT_DIR/kill-switch-response.txt" -w '%{http_code}' \
        -H "Authorization: Bearer ${ACCESS_TOKEN}" \
        -H "Content-Type: application/json" \
        -H "Idempotency-Key: acceptance-$(date +%s)-$$" \
        -X POST "$GATEWAY_URL/api/orders" \
        -d "{\"listingId\":1,\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":${QUANTITY},\"limitPrice\":${LIMIT_PRICE},\"destination\":\"${DESTINATION}\"}"
}

run_load() {
    local summary_out="$OUT_DIR/k6-summary.json"
    if [ "$PROFILE" = "1" ]; then
        JFR_SCENARIO="OMS hot-path acceptance rate=${RATE} duration=${DURATION}" \
        "$repo_root/scripts/perf/jfr-run.sh" --services order-management-service -- \
            env EMPORIA_TOKEN="$ACCESS_TOKEN" GATEWAY_URL="$GATEWAY_URL" SUMMARY_OUT="$summary_out" \
            k6 run --quiet \
                -e "RATE=${RATE}" \
                -e "DURATION=${DURATION}" \
                -e "LISTING_IDS=${LISTING_IDS}" \
                -e "DESTINATION=${DESTINATION}" \
                -e "QUANTITY=${QUANTITY}" \
                -e "LIMIT_PRICE=${LIMIT_PRICE}" \
                "$repo_root/scripts/perf/order-load.js"
    else
        EMPORIA_TOKEN="$ACCESS_TOKEN" GATEWAY_URL="$GATEWAY_URL" SUMMARY_OUT="$summary_out" \
            k6 run --quiet \
                -e "RATE=${RATE}" \
                -e "DURATION=${DURATION}" \
                -e "LISTING_IDS=${LISTING_IDS}" \
                -e "DESTINATION=${DESTINATION}" \
                -e "QUANTITY=${QUANTITY}" \
                -e "LIMIT_PRICE=${LIMIT_PRICE}" \
                "$repo_root/scripts/perf/order-load.js"
    fi
}

echo "==> Checking hot-path status"
status_json | tee "$OUT_DIR/status-before.json"

SHADOW_AFTER_SEQUENCE_ID="$(python3 - "$OUT_DIR/status-before.json" <<'PY'
import json, sys
try:
    print(int(json.load(open(sys.argv[1])).get("latestInputSequenceId") or 0))
except Exception:
    print(0)
PY
)"
echo "    shadow checkpoint sequence: ${SHADOW_AFTER_SEQUENCE_ID}"

echo "==> Running load profile RATE=${RATE}/s DURATION=${DURATION}"
run_load | tee "$OUT_DIR/k6-output.txt"

python3 - "$OUT_DIR/k6-summary.json" "$MAX_P99_MS" > "$OUT_DIR/summary.txt" <<'PY'
import json, sys
path, max_p99 = sys.argv[1], float(sys.argv[2])
data = json.load(open(path))
p50 = float(data.get("submit_latency_p50", 0))
p95 = float(data.get("submit_latency_p95", 0))
p99 = float(data.get("submit_latency_p99", 0))
accepted = data.get("orders_accepted", 0)
business = float(data.get("business_rejection_rate", 0))
infra = float(data.get("infra_failure_rate", 0))
print(f"    accepted              {accepted}")
print(f"    business rejection    {business:.4f}")
print(f"    infra failure         {infra:.4f}")
print(f"    submit latency p50    {p50:.2f} ms")
print(f"    submit latency p95    {p95:.2f} ms")
print(f"    submit latency p99    {p99:.2f} ms")
if max_p99 > 0 and p99 > max_p99:
    raise SystemExit(f"submit latency p99 {p99:.2f}ms exceeds max {max_p99:.2f}ms")
PY
cat "$OUT_DIR/summary.txt"

echo "==> Comparing this run's live outcomes against shadow replay"
sleep "${SHADOW_FLUSH_WAIT_SECONDS:-1}"
shadow_json | tee "$OUT_DIR/shadow-report.json"

python3 - "$OUT_DIR/shadow-report.json" "$REQUIRED_SHADOW_PASS_RATE" <<'PY'
import json, sys
path, required = sys.argv[1], float(sys.argv[2])
data = json.load(open(path))
total = int(data.get("totalCommands", 0))
rate = float(data.get("passRate", 0.0))
print(f"    shadow commands: {total}")
print(f"    shadow pass rate: {rate:.4f}")
if total <= 0:
    raise SystemExit("shadow replay did not compare any commands")
if rate < required:
    raise SystemExit(f"shadow pass rate {rate:.4f} < required {required:.4f}")
PY

echo "==> Verifying controlled degradation with kill switch"
engage_kill_switch
trap release_kill_switch EXIT
code="$(submit_one_order)"
if [ "$code" != "503" ]; then
    fail "expected kill-switch rejection HTTP 503, got ${code}"
fi
release_kill_switch
trap - EXIT

echo "==> Acceptance artifacts: $OUT_DIR"
echo "PASS"
