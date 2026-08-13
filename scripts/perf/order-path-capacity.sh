#!/usr/bin/env bash
# Runs the gateway -> order-management order-path capacity probe (execution
# routing is in-process inside order-management-service) and records latency
# and exchange-core checkpoint health.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8082}"
OMS_URL="${OMS_URL:-http://localhost:8086}"
PROM_URL="${PROM_URL:-http://localhost:9090}"
ORIGIN="${EMPORIA_ORIGIN:-http://localhost:3001}"
RATES="${ORDER_PATH_RATES:-${PROBE_RATES:-5 10 20 40 60}}"
PROBE_STEP="${PROBE_STEP:-60s}"
LISTING_IDS="${LISTING_IDS:-1}"
QUANTITY="${QUANTITY:-1}"
LIMIT_PRICE="${LIMIT_PRICE:-100.00}"
RUN_LABEL="${ORDER_PATH_RUN_LABEL:-jdk21-hardened}"
OUT_DIR="${ORDER_PATH_OUT_DIR:-$repo_root/.local-run/order-path-capacity/$(date +%Y%m%d-%H%M%S)-${RUN_LABEL}}"
FINAL_DRAIN_SECONDS="${FINAL_DRAIN_SECONDS:-30}"

usage() {
    cat <<EOF
Usage: scripts/perf/order-path-capacity.sh [--dry-run]

Environment:
  ORDER_PATH_RATES       Space-separated offered rates. Default: "5 10 20 40 60"
  PROBE_STEP             Duration per rate. Default: 60s
  ORDER_PATH_OUT_DIR     Output directory. Default: .local-run/order-path-capacity/<timestamp>
  GATEWAY_URL            Gateway base URL. Default: http://localhost:8082
  OMS_URL                order-management-service base URL (execution routing
                         runs in-process inside it). Default: http://localhost:8086
  PROM_URL               Prometheus base URL. Default: http://localhost:9090
  EXCHANGE_CORE_JOURNALING
                         Recorded in metadata. Use true with run-infra-docker.sh
                         to measure journalled catch-up capacity.
  EMPORIA_USERNAME       Login user for token minting. Default: admin
  EMPORIA_PASSWORD       Login password for token minting. Default: admin123
EOF
}

dry_run=false
for arg in "$@"; do
    case "$arg" in
        --help|-h) usage; exit 0 ;;
        --dry-run) dry_run=true ;;
        *) echo "Unknown argument: $arg" >&2; usage >&2; exit 2 ;;
    esac
done

fail() { echo "FAIL: $*" >&2; exit 1; }

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "$1 is required on PATH"
}

health_check() {
    local name="$1" url="$2"
    curl -fsS --max-time 10 "$url" >/dev/null 2>&1 \
        || fail "$name is not healthy at $url"
}

mint_token() {
    EMPORIA_ORIGIN="$ORIGIN" \
    EMPORIA_USERNAME="${EMPORIA_USERNAME:-admin}" \
    EMPORIA_PASSWORD="${EMPORIA_PASSWORD:-admin123}" \
        node "$repo_root/scripts/perf/get-access-token.mjs" 2>/dev/null \
        || fail "could not mint an access token from $ORIGIN"
}

mint_multi_tokens() {
    local tokens=()
    local t
    for u in admin trader_1 trader_2 trader_3 trader_4 trader_5 trader_6 trader_7 trader_8 trader_9 trader_10; do
        t="$(EMPORIA_ORIGIN="$ORIGIN" EMPORIA_USERNAME="$u" EMPORIA_PASSWORD="admin123" node "$repo_root/scripts/perf/get-access-token.mjs" 2>/dev/null || true)"
        if [ -n "$t" ]; then
            tokens+=("$t")
        fi
    done
    if [ ${#tokens[@]} -eq 0 ]; then
        fail "could not mint any multi-user access tokens"
    fi
    (IFS=,; echo "${tokens[*]}")
}

k6_field() {
    local file="$1" field="$2"
    python3 - "$file" "$field" <<'PY' 2>/dev/null || echo 0
import json
import sys
try:
    data = json.load(open(sys.argv[1], encoding="utf-8"))
    value = data.get(sys.argv[2], 0)
except Exception:
    value = 0
print(value)
PY
}

health_field() {
    local file="$1" key="$2" default="${3:-0}"
    python3 - "$file" "$key" "$default" <<'PY' 2>/dev/null || echo "$default"
import json
import sys

path, key, default = sys.argv[1:4]

def find(value, wanted):
    if isinstance(value, dict):
        if wanted in value:
            return value[wanted]
        for child in value.values():
            found = find(child, wanted)
            if found is not None:
                return found
    elif isinstance(value, list):
        for child in value:
            found = find(child, wanted)
            if found is not None:
                return found
    return None

try:
    data = json.load(open(path, encoding="utf-8"))
    value = find(data, key)
except Exception:
    value = None

if value is None:
    value = default
if isinstance(value, bool):
    print(str(value).lower())
else:
    print(value)
PY
}

prom_file_metric() {
    local file="$1"
    local metric="$2"
    local default="${3:-0}"
    python3 - "$file" "$metric" "$default" <<'PY' 2>/dev/null || echo "$default"
import sys

path, metric, default = sys.argv[1:4]
try:
    with open(path, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith(metric + " ") or line.startswith(metric + "{"):
                print(line.split()[-1])
                break
        else:
            print(default)
except Exception:
    print(default)
PY
}

capture_execution_health() {
    local label="$1"
    curl -sS --max-time 10 "${OMS_URL}/actuator/health" \
        >"${OUT_DIR}/${label}.execution-health.json" 2>/dev/null || echo '{}' \
        >"${OUT_DIR}/${label}.execution-health.json"
    curl -sS --max-time 10 "${OMS_URL}/actuator/prometheus" 2>/dev/null \
        | grep 'emporia_execution_venue_checkpoint' \
        >"${OUT_DIR}/${label}.checkpoint.prom" || true
}

capture_checkpoint_csv_fields() {
    local label="$1"
    local health="${OUT_DIR}/${label}.execution-health.json"
    local prom="${OUT_DIR}/${label}.checkpoint.prom"
    local available=false
    if grep -Eq '^emporia_execution_venue_checkpoint_age_seconds([ {])' "$prom" 2>/dev/null; then
        available=true
    fi
    printf '%s,%s,%s,%s,%s,%s,%s' \
        "$(prom_file_metric "$prom" emporia_execution_venue_checkpoint_age_seconds 0)" \
        "$(prom_file_metric "$prom" emporia_execution_venue_checkpoint_files 0)" \
        "$(prom_file_metric "$prom" emporia_execution_venue_checkpoint_partial_files 0)" \
        "$(prom_file_metric "$prom" emporia_execution_venue_checkpoint_storage_bytes 0)" \
        "$(prom_file_metric "$prom" emporia_execution_venue_checkpoint_storage_usable_bytes 0)" \
        "$(health_field "$health" checkpointFailuresSinceLastSuccess 0)" \
        "$available"
}

active_orders() {
    docker exec "${PG_CONTAINER:-emporia-order-management-postgres}" psql -U "${PG_USER:-postgres}" \
        -d "${PG_DB:-emporia_order_management}" -tAc \
        "select count(*) from emporia_order_data.trading_order where order_status in ('LIVE','PARTIALLY_FILLED');" \
        2>/dev/null | tr -d ' ' || echo unknown
}

portfolio_balance() {
    docker exec "${PORTFOLIO_PG_CONTAINER:-emporia-portfolio-postgres}" psql -U "${PORTFOLIO_PG_USER:-postgres}" \
        -d "${PORTFOLIO_PG_DB:-emporia_portfolio}" -tAc \
        "select coalesce(sum(available_balance), 0) from emporia_portfolio.portfolio_balance where asset_id = 840;" \
        2>/dev/null | tr -d ' ' || echo unknown
}

write_table() {
    python3 - "$OUT_DIR/summary.csv" <<'PY'
import csv
import sys

rows = list(csv.DictReader(open(sys.argv[1], encoding="utf-8")))
headers = [
    ("rate", "Offered rate"),
    ("accepted", "Accepted"),
    ("infra_failure_rate", "Infra failures"),
    ("business_rejection_rate", "Business rejections"),
    ("p50_ms", "p50"),
    ("p95_ms", "p95"),
    ("p99_ms", "p99"),
    ("checkpoint_age_seconds", "Checkpoint age"),
    ("checkpoint_files", "Checkpoint files"),
    ("partial_checkpoint_files", "Partial files"),
]

def fmt(row, key):
    value = row.get(key, "")
    if key == "rate":
        return f"{value}/s"
    if key in {"infra_failure_rate", "business_rejection_rate"}:
        return f"{float(value) * 100:.0f}%"
    if key in {"p50_ms", "p95_ms", "p99_ms"}:
        return f"{float(value):.2f} ms"
    if key in {"accepted", "checkpoint_age_seconds", "checkpoint_files", "partial_checkpoint_files"}:
        return f"{float(value):,.0f}"
    return value

print("| " + " | ".join(label for _, label in headers) + " |")
print("| " + " | ".join("---" for _ in headers) + " |")
for row in rows:
    print("| " + " | ".join(fmt(row, key) for key, _ in headers) + " |")
PY
}

run_k6_step() {
    local rate="$1" token="$2" summary="$3" log="$4" tokens="$5"
    EMPORIA_TOKEN="$token" \
    EMPORIA_TOKENS="${tokens:-$token}" \
    MIX_SIDES="${MIX_SIDES:-false}" \
    GATEWAY_URL="$GATEWAY_URL" \
    SUMMARY_OUT="$summary" \
        k6 run --quiet \
            -e "RATE=${rate}" \
            -e "DURATION=${PROBE_STEP}" \
            -e "LISTING_IDS=${LISTING_IDS}" \
            -e "QUANTITY=${QUANTITY}" \
            -e "LIMIT_PRICE=${LIMIT_PRICE}" \
            -e "MIX_SIDES=${MIX_SIDES:-false}" \
            "$repo_root/scripts/perf/order-load.js" >"$log" 2>&1
}

require_command curl
require_command node
require_command python3
require_command k6

health_check gateway "${GATEWAY_URL}/actuator/health"
health_check order-management "${OMS_URL}/actuator/health"
health_check prometheus "${PROM_URL}/-/healthy"

if [ "$dry_run" = true ]; then
    echo "[Dry-Run Mode] Prerequisites verified."
    exit 0
fi

mkdir -p "$OUT_DIR"

cat >"${OUT_DIR}/metadata.txt" <<EOF
output_dir=${OUT_DIR}
started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
gateway_url=${GATEWAY_URL}
oms_url=${OMS_URL}
prometheus_url=${PROM_URL}
quantity=${QUANTITY}
limit_price=${LIMIT_PRICE}
listing_ids=${LISTING_IDS}
rates=${RATES}
probe_step=${PROBE_STEP}
exchange_core_journaling=${EXCHANGE_CORE_JOURNALING:-false}
exchange_core_snapshot_interval=${EXCHANGE_CORE_SNAPSHOT_INTERVAL:-60s}
exchange_core_retained_checkpoints=${EXCHANGE_CORE_RETAINED_CHECKPOINTS:-2}
java_version=$(java -version 2>&1 | head -1)
k6_version=$(k6 version 2>/dev/null | head -1)
git_head=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)
git_dirty=$(git status --short | wc -l | tr -d ' ')
active_orders_start=$(active_orders)
portfolio_usd_balance_start=$(portfolio_balance)
EOF

echo "==> Order path capacity benchmark"
echo "    output: ${OUT_DIR}"
echo "    rates: ${RATES}"
echo "    duration per rate: ${PROBE_STEP}"

capture_execution_health "before"

cat >"${OUT_DIR}/summary.csv" <<'EOF'
rate,status,accepted,business_rejection_rate,infra_failure_rate,p50_ms,p95_ms,p99_ms,checkpoint_age_seconds,checkpoint_files,partial_checkpoint_files,checkpoint_storage_bytes,checkpoint_usable_storage_bytes,checkpoint_failures_since_last_success,checkpoint_status_available
EOF

overall_status=0
for rate in $RATES; do
    echo "  -- offered ${rate}/s"
    token="$(mint_token)"
    tokens="$token"
    if [ "${MULTI_USER:-false}" = "true" ]; then
        tokens="$(mint_multi_tokens)"
    fi
    summary="${OUT_DIR}/rate-${rate}.k6.json"
    log="${OUT_DIR}/rate-${rate}.k6.log"
    status=0
    run_k6_step "$rate" "$token" "$summary" "$log" "$tokens" || status=$?
    tail -20 "$log" | sed 's/^/     /'

    capture_execution_health "rate-${rate}"
    checkpoint_fields="$(capture_checkpoint_csv_fields "rate-${rate}")"

    printf '%s,%s,%s,%s,%s,%.2f,%.2f,%.2f,%s\n' \
        "$rate" \
        "$status" \
        "$(k6_field "$summary" orders_accepted)" \
        "$(k6_field "$summary" business_rejection_rate)" \
        "$(k6_field "$summary" infra_failure_rate)" \
        "$(k6_field "$summary" submit_latency_p50)" \
        "$(k6_field "$summary" submit_latency_p95)" \
        "$(k6_field "$summary" submit_latency_p99)" \
        "$checkpoint_fields" >>"${OUT_DIR}/summary.csv"

    echo "     checkpoint fields ${checkpoint_fields}"
    if [ "$status" -ne 0 ]; then
        overall_status="$status"
        echo "     stopping after k6 exit status ${status}"
        break
    fi
done

echo "==> Quiet window (${FINAL_DRAIN_SECONDS}s)"
sleep "$FINAL_DRAIN_SECONDS"
capture_execution_health "after"
{
    echo "active_orders_end=$(active_orders)"
    echo "portfolio_usd_balance_end=$(portfolio_balance)"
    echo "finished_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} >>"${OUT_DIR}/metadata.txt"

{
    echo "Emporia Order Path Capacity Benchmark"
    echo
    echo "Run directory: ${OUT_DIR}"
    echo "Gateway URL: ${GATEWAY_URL}"
    echo "Workload: scripts/perf/order-load.js via k6, quantity=${QUANTITY}, limitPrice=${LIMIT_PRICE}, ${PROBE_STEP} per rate"
    echo "Exchange-core journaling: ${EXCHANGE_CORE_JOURNALING:-false}, snapshot interval: ${EXCHANGE_CORE_SNAPSHOT_INTERVAL:-60s}"
    echo "Runtime: $(java -version 2>&1 | head -1)"
    echo
    echo "Summary:"
    write_table
    echo
    echo "Raw CSV:"
    cat "${OUT_DIR}/summary.csv"
    echo
    echo "Post-run:"
    grep '^final_' "${OUT_DIR}/metadata.txt" || true
    grep '^active_orders_' "${OUT_DIR}/metadata.txt" || true
    grep '^portfolio_usd_balance_' "${OUT_DIR}/metadata.txt" || true
    echo
    echo "Notes:"
    echo "- This script does not reset Docker volumes or delete exchange-core storage."
    echo "- For a clean-book local run, stop the stack and run scripts/perf/reset-venue-state.sh --yes deliberately before this benchmark."
    echo "- Checkpoint age/file/storage fields are captured from order-management-service Prometheus metrics after each rate."
} >"${OUT_DIR}/run-notes.txt"

cat "${OUT_DIR}/run-notes.txt"
exit "$overall_status"
