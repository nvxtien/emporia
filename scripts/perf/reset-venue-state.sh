#!/bin/bash
# Bulk-clears exchange-core engine state and reconciles order-management-service
# so a capacity baseline starts from a known, empty book.
#
# Usage: scripts/perf/reset-venue-state.sh --yes
#
# Why this exists: the resting order book grows during load runs, and
# exchange-core checkpoints serialise engine state, so venue operation latency
# decays as the book fills (PHASE_1_4_JFR_RESULTS.md: 60ms -> 172ms p50 as the
# book went from ~600 to 3,356 orders). A baseline taken on a full book is not
# reproducible, and cancelling thousands of orders one at a time would take
# longer than the measurement and distort the very state being reset.
#
# THIS IS DESTRUCTIVE. It force-cancels every working order and discards the
# venue's entire engine state. It is a local performance-testing tool, not an
# operational procedure.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

PG_CONTAINER="${PG_CONTAINER:-emporia-order-management-postgres}"
PG_DB="${PG_DB:-emporia_order_management}"
PG_USER="${PG_USER:-postgres}"
STORAGE_DIR="${EXCHANGE_CORE_STORAGE_DIRECTORY:-$repo_root/execution-service/.local-run/exchange-core-simulation}"
REASON="${RESET_REASON:-Force-cancelled by scripts/perf/reset-venue-state.sh before a capacity baseline}"

fail() { echo "FAIL: $*" >&2; exit 1; }

[ "${1:-}" = "--yes" ] || fail "refusing to run without --yes.
  This force-cancels every working order and deletes the exchange-core engine
  state at:
    ${STORAGE_DIR}
  Intended for local performance testing only."

psql_q() { docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc "$1" 2>/dev/null; }

docker exec "$PG_CONTAINER" true >/dev/null 2>&1 \
    || fail "postgres container '${PG_CONTAINER}' is not running"

working="$(psql_q "select count(*) from emporia_order_data.trading_order where order_status in ('LIVE','PARTIALLY_FILLED');")"
echo "==> Resetting venue state"
echo "    working orders in order-management: ${working:-0}"
echo "    engine storage: ${STORAGE_DIR} ($(du -sh "$STORAGE_DIR" 2>/dev/null | awk '{print $1}' || echo absent))"

# Stop the venue first. Clearing storage under a running engine leaves it
# writing snapshots into a directory that no longer describes its state.
pid_file="$repo_root/.local-run/pids/execution-service.pid"
restart_needed=false
if [ -f "$pid_file" ]; then
    launcher="$(cat "$pid_file")"
    if kill -0 "$launcher" 2>/dev/null; then
        echo "==> Stopping execution-service"
        # The pid file records the mvn launcher; the application is its child.
        child="$(pgrep -P "$launcher" 2>/dev/null | head -1)"
        [ -n "$child" ] && kill -TERM "$child" 2>/dev/null || true
        kill -TERM "$launcher" 2>/dev/null || true
        while kill -0 "$launcher" 2>/dev/null; do sleep 1; done
        restart_needed=true
    fi
fi
# Catch application JVMs orphaned from an earlier run: they hold the engine and
# would keep writing to the directory being cleared.
pkill -TERM -f "execution-service" 2>/dev/null || true
sleep 3

echo "==> Clearing exchange-core engine state"
rm -rf "$STORAGE_DIR"
echo "    removed ${STORAGE_DIR}"

# Reconcile OMS in one statement. Without this, order-management keeps thousands
# of orders it believes are working against an engine that has never heard of
# them, and the next strategy tick tries to act on every one.
echo "==> Force-cancelling working orders in order-management"
cancelled="$(psql_q "
    update emporia_order_data.trading_order
       set order_status  = 'CANCELLED',
           target_status = 'CANCELLED',
           error_message = '${REASON}',
           updated_at    = now()
     where order_status in ('LIVE','PARTIALLY_FILLED')
    returning 1;" | grep -c 1 || true)"
echo "    cancelled ${cancelled:-0} orders"

remaining="$(psql_q "select count(*) from emporia_order_data.trading_order where order_status in ('LIVE','PARTIALLY_FILLED');")"
[ "${remaining:-0}" = "0" ] || fail "expected no working orders after reset, found ${remaining}"

if [ "$restart_needed" = true ]; then
    echo "==> Restarting execution-service on a clean engine"
    ( cd "$repo_root/execution-service" && exec env \
        DB_URL="${DB_URL:-jdbc:postgresql://localhost:5437/emporia_execution}" \
        DB_PASSWORD="${DB_PASSWORD:-admin123}" \
        EXECUTION_VENUE_MODE="${EXECUTION_VENUE_MODE:-exchange-core}" \
        EXCHANGE_CORE_ACCOUNTING_MODE="${EXCHANGE_CORE_ACCOUNTING_MODE:-full-equity-risk}" \
        EXCHANGE_CORE_PORTFOLIO_URL="${EXCHANGE_CORE_PORTFOLIO_URL:-http://localhost:8088}" \
        mvn spring-boot:run ) > "$repo_root/.local-run/logs/execution-service.log" 2>&1 &
    echo "$!" > "$pid_file"
    printf "    waiting for execution-service"
    for _ in $(seq 1 60); do
        curl -fsS http://localhost:8087/actuator/health >/dev/null 2>&1 && break
        printf '.'; sleep 3
    done
    echo
    curl -fsS http://localhost:8087/actuator/health >/dev/null 2>&1 \
        && echo "    up on a clean engine" \
        || fail "execution-service did not come back up; see .local-run/logs/execution-service.log"
fi

echo "==> Reset complete: 0 working orders, empty engine state"
