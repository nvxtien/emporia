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
#
# TWO ORDERS ARE NOT ENOUGH, and for a long time that was all this checked.
# Journalling is a Disruptor stage running in parallel with matching, so at rest
# every command is its own batch and flushes promptly - the journal never
# perforates and this test could not fail. Under load the batches are large and
# a kill loses whole ones: measured 2026-08-17, 2,416 of 3,000 orders submitted
# at 300/sec vanished from the engine while order-management held them all as
# LIVE. See CONFIGURATION.md, "The venue's journal does not recover process
# death".
#
# So there is now a load stage, and the assertion is a reconciliation delta
# rather than "can we cancel one order". Any order acknowledged 201 that the
# venue cannot account for afterwards is a failure.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8082}"
EXECUTION_URL="${EXECUTION_URL:-http://localhost:8086}"
ORIGIN="${EMPORIA_ORIGIN:-http://localhost:3001}"
PG_CONTAINER="${PG_CONTAINER:-emporia-order-management-postgres}"
PG_DB="${PG_DB:-emporia_order_management}"
PG_USER="${PG_USER:-postgres}"
pid_file="$repo_root/.local-run/pids/order-management-service.pid"
# The load stage. Rate matters more than volume: it has to be high enough that
# the journal batches, which is the only condition under which the failure this
# checks for can occur.
LOAD_RATE="${CRASH_LOAD_RATE:-300}"
LOAD_DURATION="${CRASH_LOAD_DURATION:-10s}"
# Orders acknowledged 201 and then lost. Zero, because the kill happens after the
# load has finished and nothing is in flight - every loss here is an order a
# client was told was resting.
MISSING_TOLERANCE="${CRASH_MISSING_TOLERANCE:-0}"

fail() { echo "FAIL: $*" >&2; exit 1; }
psql_q() { docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc "$1" 2>/dev/null; }

mint_token() {
    EMPORIA_ORIGIN="$ORIGIN" \
    EMPORIA_USERNAME="${EMPORIA_USERNAME:-admin}" \
    EMPORIA_PASSWORD="${EMPORIA_PASSWORD:-admin123}" \
        node "$repo_root/scripts/perf/get-access-token.mjs" 2>/dev/null \
        || fail "could not mint an access token (is the stack up on ${ORIGIN}?)"
}

# Missing count from the venue reconciliation, or a hard failure. Never returns
# a number it could not actually measure: an unanswerable venue reported as
# "0 missing" is how a check quietly stops being one.
reconcile_missing() {
    local token; token="$(mint_token)"
    curl -fsS --max-time 300 -H "Authorization: Bearer ${token}" \
        "${EXECUTION_URL}/actuator/reconciliation" \
        | python3 -c '
import sys, json
d = json.load(sys.stdin)
if not d.get("supported"):
    sys.stderr.write("venue cannot enumerate open orders; this check cannot run\n")
    sys.exit(1)
print(d["missingCount"])' \
        || fail "could not read ${EXECUTION_URL}/actuator/reconciliation"
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
grep -q "journaling=true" "$repo_root/.local-run/logs/order-management-service.log" \
    || fail "order-management-service is not running with journaling=true.
  Start it with EXCHANGE_CORE_JOURNALING=true, or this proves nothing: without
  the journal the engine snapshots per command and cannot lose the window this
  test exists to check."

command -v k6 >/dev/null 2>&1 \
    || fail "k6 is required. Without the load stage this check cannot reproduce the
  failure it exists for, and a check that cannot fail is worse than no check."

token="$(mint_token)"

echo "==> Baseline reconciliation"
missing_before="$(reconcile_missing)"
echo "    venue is missing ${missing_before} order(s) before the test"

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

# The window that matters. Everything submitted from here until the kill exists
# only in the journal, and at this rate it exists in *batched* journal writes.
echo "==> ${LOAD_DURATION} of load at ${LOAD_RATE}/sec, journal-only"
checkpoint_file="$repo_root/order-management-service/.local-run/exchange-core-simulation/emporia-exchange-core.latest"
cp_before="$(grep -o '[0-9]*' "$checkpoint_file" 2>/dev/null | head -1 || echo unknown)"
EMPORIA_TOKEN="$token" k6 run --quiet \
    -e "RATE=${LOAD_RATE}" -e "DURATION=${LOAD_DURATION}" \
    "$repo_root/scripts/perf/order-load.js" 2>&1 | grep -E "accepted|rejections" | sed 's/^ */    /'
cp_after="$(grep -o '[0-9]*' "$checkpoint_file" 2>/dev/null | head -1 || echo unknown)"
if [ "$cp_before" != "$cp_after" ]; then
    echo "    NOTE: a snapshot landed during the load (${cp_before} -> ${cp_after})."
    echo "          Part of the window is now covered by it, so this run is weaker"
    echo "          than intended. Shorten CRASH_LOAD_DURATION or raise the snapshot interval."
fi

echo "==> kill -9 order-management-service"
launcher="$(cat "$pid_file" 2>/dev/null)" || fail "no order-management-service pid file"
child="$(pgrep -P "$launcher" 2>/dev/null | head -1)"
[ -n "$child" ] || fail "could not find the application JVM under launcher ${launcher}"
kill -9 "$child" "$launcher" 2>/dev/null || true
while kill -0 "$child" 2>/dev/null; do sleep 1; done
echo "    killed jvm ${child}"

# -Dmatching is load-bearing: `agency` is the default profile (activated by
# !matching) and does not carry the exchange-core dependency at all, so a plain
# `mvn spring-boot:run` here restarts a build with no matching engine and
# EXECUTION_VENUE_MODE=exchange-core then has no gateway to route to. The profile
# rename made that true without anything failing until this script was next run.
echo "==> Restarting"
( cd "$repo_root/order-management-service" && exec env \
    DB_URL="${DB_URL:-jdbc:postgresql://localhost:5436/emporia_order_management}" \
    DB_PASSWORD="${DB_PASSWORD:-admin123}" \
    EXECUTION_VENUE_MODE="${EXECUTION_VENUE_MODE:-exchange-core}" \
    EXCHANGE_CORE_ACCOUNTING_MODE="${EXCHANGE_CORE_ACCOUNTING_MODE:-full-equity-risk}" \
    EXCHANGE_CORE_PORTFOLIO_URL="${EXCHANGE_CORE_PORTFOLIO_URL:-http://localhost:8088}" \
    EXCHANGE_CORE_JOURNALING=true \
    mvn -Dmatching -DskipTests spring-boot:run ) \
        > "$repo_root/.local-run/logs/order-management-service.log" 2>&1 &
echo "$!" > "$pid_file"

printf "    waiting"
for _ in $(seq 1 80); do
    curl -fsS "${EXECUTION_URL}/actuator/health/liveness" >/dev/null 2>&1 && break
    printf '.'; sleep 3
done
echo
curl -fsS "${EXECUTION_URL}/actuator/health/liveness" >/dev/null 2>&1 \
    || fail "order-management-service did not come back up; see .local-run/logs/order-management-service.log"

echo "==> Verifying recovery"
restored="$(grep -c "Rebuilt venue lifecycle from order-management" \
        "$repo_root/.local-run/logs/order-management-service.log" || true)"
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
# cold restart it competes with recovery still rebuilding runtime state, so a
# fixed short sleep here was producing false FAILs on a system that had
# actually recovered correctly. Poll instead of guessing a single wait.
status_b="$(order_status "$order_b")"
for _ in $(seq 1 15); do
    [ "$status_b" = "CANCELLED" ] && break
    sleep 2
    status_b="$(order_status "$order_b")"
done
[ "$status_b" = "CANCELLED" ] \
    || fail "order B is ${status_b}, not CANCELLED after 30s. The venue could not act on an
  order accepted after the last snapshot, which is the failure this checks for.
  Look for 'unknown lifecycle order' in .local-run/logs/order-management-service.log."

if grep -q "unknown lifecycle order" "$repo_root/.local-run/logs/order-management-service.log"; then
    fail "recovery logged 'unknown lifecycle order' - the engine and the lifecycle disagree"
fi

# The assertion the two-order version could not make. Everything above proves
# the lifecycle rebuild works; this proves the engine still holds what clients
# were told it holds.
echo "==> Reconciling against the venue"
missing_after="$(reconcile_missing)"
lost=$(( missing_after - missing_before ))
echo "    missing before ${missing_before}, after ${missing_after}, delta ${lost}"
[ "$lost" -le "$MISSING_TOLERANCE" ] \
    || fail "the venue lost ${lost} order(s) that order-management still holds as LIVE.
  They were acknowledged 201, they are durable in PostgreSQL, and they cannot fill.
  Look for 'Sequence gap' in .local-run/logs/order-management-service.log - a hard
  kill perforates the journal rather than truncating it, and replay warns and
  continues. See CONFIGURATION.md, \"The venue's journal does not recover process
  death\"."

echo "==> PASS: order B survived kill -9 and the venue can act on it"
echo "    order A ${order_a} -> $(order_status "$order_a")"
echo "    order B ${order_b} -> ${status_b}"
