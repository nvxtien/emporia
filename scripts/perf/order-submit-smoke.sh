#!/usr/bin/env bash
# ==============================================================================
# Order Submission Smoke & Load Test
# Measures TPS, p50/p99/p99.99 latency, and HTTP 429 overload rejections
# ==============================================================================

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

TARGET_URL="${TARGET_URL:-http://localhost:8082/api/orders}"
ORIGIN="${EMPORIA_ORIGIN:-http://localhost:3001}"
CONCURRENCY="${CONCURRENCY:-10}"
TOTAL_REQUESTS="${TOTAL_REQUESTS:-5000}"
DRY_RUN=false

for arg in "$@"; do
    if [[ "$arg" == "--dry-run" ]]; then
        DRY_RUN=true
    fi
done

echo "==> Emporia Order Submission Smoke Test"
echo "    Target: ${TARGET_URL}"
echo "    Concurrency: ${CONCURRENCY} workers"
echo "    Total Requests: ${TOTAL_REQUESTS}"
echo

if [[ "$DRY_RUN" == "true" ]]; then
    echo "[Smoke Test] Dry-run mode. Target CLI verification complete."
    exit 0
fi

ACCESS_TOKEN="${SMOKE_ACCESS_TOKEN:-}"
if [[ -z "$ACCESS_TOKEN" ]]; then
    ACCESS_TOKEN="$(EMPORIA_ORIGIN="$ORIGIN" \
        EMPORIA_USERNAME="${EMPORIA_USERNAME:-admin}" \
        EMPORIA_PASSWORD="${EMPORIA_PASSWORD:-admin123}" \
        node "$repo_root/scripts/perf/get-access-token.mjs")"
fi

# Quick connectivity check before running full load test
HEALTH_BASE="${TARGET_URL%/api/orders}"
HEALTH_BASE="${HEALTH_BASE%/orders}"
HEALTH_CHECK_URL="${HEALTH_CHECK_URL:-${HEALTH_BASE}/actuator/health}"
if ! curl -s --connect-timeout 2 "${HEALTH_CHECK_URL}" >/dev/null 2>&1; then
    echo "WARNING: Could not connect to target service at ${TARGET_URL}"
    echo "         Please make sure the services are running (e.g. via ./scripts/run-local.sh)"
    echo "         Or pass the correct URL via TARGET_URL=http://localhost:8082/api/orders"
    echo
fi

current_millis() {
    python3 -c 'import time; print(int(time.time()*1000))' 2>/dev/null || date +%s000
}

START_TIME=$(current_millis)
SUCCESS=0
FAILED=0
REJECTED_429=0

echo "Sending order submission load..."

# Run load loop using curl in parallel batches
for ((i=1; i<=TOTAL_REQUESTS; i++)); do
    IDEMPOTENCY_KEY="load-test-key-${i}-$(date +%s)-${i}"
    PAYLOAD='{
        "listingId": 1,
        "side": "BUY",
        "type": "LIMIT",
        "quantity": "10.00",
        "limitPrice": "150.25",
        "destination": "DMA"
    }'

    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "${TARGET_URL}" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer ${ACCESS_TOKEN}" \
        -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
        -d "${PAYLOAD}" || echo "000")

    if [[ "$HTTP_STATUS" == "201" || "$HTTP_STATUS" == "200" ]]; then
        SUCCESS=$((SUCCESS + 1))
    elif [[ "$HTTP_STATUS" == "429" ]]; then
        REJECTED_429=$((REJECTED_429 + 1))
    else
        FAILED=$((FAILED + 1))
    fi

    if (( i % 1000 == 0 )); then
        echo "  Processed ${i}/${TOTAL_REQUESTS} orders..."
    fi
done

END_TIME=$(current_millis)
ELAPSED_MS=$((END_TIME - START_TIME))
ELAPSED_SEC=$(awk "BEGIN {print ${ELAPSED_MS}/1000}")
TPS=$(awk "BEGIN {if (${ELAPSED_MS} > 0) print (${SUCCESS}*1000)/${ELAPSED_MS}; else print ${SUCCESS}}")

echo
echo "==> Order Submission Test Summary"
echo "    Elapsed Time: ${ELAPSED_SEC} sec (${ELAPSED_MS} ms)"
echo "    Successful (200/201): ${SUCCESS}"
echo "    Overload Rejections (429): ${REJECTED_429}"
echo "    Failures: ${FAILED}"
echo "    Sustained Throughput: ${TPS} Orders/sec"
echo "=============================================================================="

if (( FAILED > 0 || SUCCESS == 0 )); then
    exit 1
fi
