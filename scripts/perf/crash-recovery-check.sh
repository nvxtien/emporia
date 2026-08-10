#!/bin/bash
# Proves the venue survives a hard kill with journaling on.
#
# Usage: scripts/perf/crash-recovery-check.sh
#
# This is the test that decides whether journalled checkpointing is safe to
# enable. Moving snapshots off the command path is only sound if everything
# accepted since the last snapshot comes back, and two separate mechanisms have
# to hold for that:
#
#   the journal        restores the matching engine
#   the OMS rebuild    restores the DMA order lifecycle
#
# The engine alone is not enough. Before the lifecycle rebuild existed, recovery
# produced a book holding orders the lifecycle could not resolve, and every
# later operation on them failed with "unknown lifecycle order" - see
# rework/WAL_LIFECYCLE_GAP.md. So this checks both, and a pass requires both.
#
# Deliberately kill -9, not a graceful stop: a clean shutdown snapshots on the
# way out, which would hide exactly the window being tested.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8082}"
EXECUTION_URL="${EXECUTION_URL:-http://localhost:8087}"
ORIGIN="${EMPORIA_ORIGIN:-http://localhost:3001}"
PG_CONTAINER="${PG_CONTAINER:-emporia-order-management-postgres}"
PG_DB="${PG_DB:-emporia_order_management}"
PG_USER="${PG_USER:-postgres}"
pid_file="$repo_root/.local-run/pids/execution-service.pid"

fail() { echo "FAIL: $*" >&2; exit 1; }
psql_q() { docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc "$1" 2>/dev/null; }

mint_token() {
    EMPORIA_ORIGIN="$ORIGIN" \
    EMPORIA_USERNAME="${EMPORIA_USERNAME:-admin}" \
    EMPORIA_PASSWORD="${EMPORIA_PASSWORD:-admin123}" \
        node "$repo_root/scripts/perf/get-access-token.mjs" 2>/dev/null \
        || fail "could not mint an access token (is the stack up on ${ORIGIN}?)"
}

submit_order() {
    local token="$1" label="$2" listing="${3:-1}"
    curl -fsS -X POST "${GATEWAY_URL}/api/orders" \
        -H "Authorization: Bearer ${token}" \
        -H "Content-Type: application/json" \
        -H "Idempotency-Key: crash-recovery-${label}-$(date +%s%N)" \
        -d "{\"listingId\":${listing},\"side\":\"BUY\",\"type\":\"LIMIT\",
             \"quantity\":\"1\",\"limitPrice\":\"100.00\",\"destination\":\"DMA\"}" \
        | python3 -c 'import sys, json; print(json.load(sys.stdin)["id"])'
}

order_status() { psql_q "select order_status from emporia_order_data.trading_order where id = '$1';"; }

echo "==> Checking journaling is enabled"
grep -q "journaling=true" "$repo_root/.local-run/logs/execution-service.log" \
    || fail "execution-service is not running with journaling=true.
  Start it with EXCHANGE_CORE_JOURNALING=true, or this proves nothing: without
  the journal the engine snapshots per command and cannot lose the window this
  test exists to check."

token="$(mint_token)"

echo "==> Submitting order A (will be covered by a snapshot)"
order_a="$(submit_order "$token" a)"
echo "    ${order_a}"

echo "==> Waiting for a periodic snapshot to cover it"
# Snapshots are on a timer, so wait one full interval plus a margin.
interval="${EXCHANGE_CORE_SNAPSHOT_INTERVAL_SECONDS:-60}"
sleep "$((interval + 10))"

echo "==> Submitting order B (journal only, after the snapshot)"
order_b="$(submit_order "$token" b)"
echo "    ${order_b}"
sleep 3

[ "$(order_status "$order_a")" = "LIVE" ] || fail "order A is not LIVE before the kill"
[ "$(order_status "$order_b")" = "LIVE" ] || fail "order B is not LIVE before the kill"

echo "==> kill -9 execution-service"
launcher="$(cat "$pid_file" 2>/dev/null)" || fail "no execution-service pid file"
child="$(pgrep -P "$launcher" 2>/dev/null | head -1)"
[ -n "$child" ] || fail "could not find the application JVM under launcher ${launcher}"
kill -9 "$child" "$launcher" 2>/dev/null || true
while kill -0 "$child" 2>/dev/null; do sleep 1; done
echo "    killed jvm ${child}"

echo "==> Restarting"
( cd "$repo_root/execution-service" && exec env \
    DB_URL="${DB_URL:-jdbc:postgresql://localhost:5437/emporia_execution}" \
    DB_PASSWORD="${DB_PASSWORD:-admin123}" \
    EXECUTION_VENUE_MODE="${EXECUTION_VENUE_MODE:-exchange-core}" \
    EXCHANGE_CORE_ACCOUNTING_MODE="${EXCHANGE_CORE_ACCOUNTING_MODE:-full-equity-risk}" \
    EXCHANGE_CORE_PORTFOLIO_URL="${EXCHANGE_CORE_PORTFOLIO_URL:-http://localhost:8088}" \
    EXCHANGE_CORE_JOURNALING=true \
    mvn spring-boot:run ) > "$repo_root/.local-run/logs/execution-service.log" 2>&1 &
echo "$!" > "$pid_file"

printf "    waiting"
for _ in $(seq 1 80); do
    curl -fsS "${EXECUTION_URL}/actuator/health/liveness" >/dev/null 2>&1 && break
    printf '.'; sleep 3
done
echo
curl -fsS "${EXECUTION_URL}/actuator/health/liveness" >/dev/null 2>&1 \
    || fail "execution-service did not come back up; see .local-run/logs/execution-service.log"

echo "==> Verifying recovery"
restored="$(grep -c "Rebuilt venue lifecycle from order-management" \
        "$repo_root/.local-run/logs/execution-service.log" || true)"
[ "$restored" -gt 0 ] \
    || fail "the lifecycle was never rebuilt from order-management, so the venue
  opened with whatever the snapshot held. Order B cannot be resolvable."

# Cancelling is the real proof. It requires the venue to resolve order B in its
# lifecycle, which is precisely what failed before the rebuild existed - the
# book held the order but the lifecycle raised "unknown lifecycle order".
echo "==> Cancelling order B, which requires the venue to resolve it"
token="$(mint_token)"
curl -fsS -X POST "${GATEWAY_URL}/api/orders/${order_b}/cancel" \
    -H "Authorization: Bearer ${token}" \
    -H "Idempotency-Key: crash-recovery-cancel-b-$(date +%s%N)" >/dev/null \
    || fail "cancel of order B was rejected outright"

# Venue-confirmed cancellation, not an immediate DB write - and right after a
# cold restart it competes with Kafka consumer-group rebalancing, so a fixed
# short sleep here was producing false FAILs on a system that had actually
# recovered correctly. Poll instead of guessing a single wait.
status_b="$(order_status "$order_b")"
for _ in $(seq 1 15); do
    [ "$status_b" = "CANCELLED" ] && break
    sleep 2
    status_b="$(order_status "$order_b")"
done
[ "$status_b" = "CANCELLED" ] \
    || fail "order B is ${status_b}, not CANCELLED after 30s. The venue could not act on an
  order accepted after the last snapshot, which is the failure this checks for.
  Look for 'unknown lifecycle order' in .local-run/logs/execution-service.log."

if grep -q "unknown lifecycle order" "$repo_root/.local-run/logs/execution-service.log"; then
    fail "recovery logged 'unknown lifecycle order' - the engine and the lifecycle disagree"
fi

echo "==> PASS: order B survived kill -9 and the venue can act on it"
echo "    order A ${order_a} -> $(order_status "$order_a")"
echo "    order B ${order_b} -> ${status_b}"
