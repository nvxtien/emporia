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
# Also clears the exchange-core portfolio outbox backlog (the durable queue
# that publishes exchange-core's own balance snapshots back to
# portfolio-service). Without this, a stale queued snapshot from before the
# reset can silently overwrite a freshly seeded balance minutes later,
# making a portfolio re-seed look like it "didn't take" - see CONFIGURATION.md.
#
# THIS IS DESTRUCTIVE. It force-cancels every working order, discards the
# venue's entire engine state, and drops its portfolio outbox backlog. It is
# a local performance-testing tool, not an operational procedure.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
log_dir="$repo_root/.local-run/logs"
pid_dir="$repo_root/.local-run/pids"

# shellcheck source=../lib/run-common.sh
source "$repo_root/scripts/lib/run-common.sh"

PG_CONTAINER="${PG_CONTAINER:-emporia-order-management-postgres}"
PG_DB="${PG_DB:-emporia_order_management}"
PG_USER="${PG_USER:-postgres}"
EXECUTION_PG_CONTAINER="${EXECUTION_PG_CONTAINER:-emporia-execution-postgres}"
EXECUTION_PG_DB="${EXECUTION_PG_DB:-emporia_execution}"
EXECUTION_PG_USER="${EXECUTION_PG_USER:-postgres}"
# Execution routing runs in-process inside order-management-service, so its
# exchange-core storage lives under that service's own working directory.
STORAGE_DIR="${EXCHANGE_CORE_STORAGE_DIRECTORY:-$repo_root/order-management-service/.local-run/exchange-core-simulation}"
REASON="${RESET_REASON:-Force-cancelled by scripts/perf/reset-venue-state.sh before a capacity baseline}"

fail() { echo "FAIL: $*" >&2; exit 1; }

[ "${1:-}" = "--yes" ] || fail "refusing to run without --yes.
  This force-cancels every working order, deletes the exchange-core engine
  state at:
    ${STORAGE_DIR}
  and drops the exchange-core portfolio outbox backlog.
  Intended for local performance testing only."

psql_q() { docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc "$1" 2>/dev/null; }
execution_psql_q() { docker exec "$EXECUTION_PG_CONTAINER" psql -U "$EXECUTION_PG_USER" -d "$EXECUTION_PG_DB" -tAc "$1" 2>/dev/null; }

docker exec "$PG_CONTAINER" true >/dev/null 2>&1 \
    || fail "postgres container '${PG_CONTAINER}' is not running"
docker exec "$EXECUTION_PG_CONTAINER" true >/dev/null 2>&1 \
    || fail "postgres container '${EXECUTION_PG_CONTAINER}' is not running"

working="$(psql_q "select count(*) from emporia_order_data.trading_order where order_status in ('LIVE','PARTIALLY_FILLED');")"
echo "==> Resetting venue state"
echo "    working orders in order-management: ${working:-0}"
echo "    engine storage: ${STORAGE_DIR} ($(du -sh "$STORAGE_DIR" 2>/dev/null | awk '{print $1}' || echo absent))"

# Stop the service first. Clearing storage under a running engine leaves it
# writing snapshots into a directory that no longer describes its state.
pid_file="$pid_dir/order-management-service.pid"
if [ -f "$pid_file" ]; then
    launcher="$(cat "$pid_file")"
    if kill -0 "$launcher" 2>/dev/null; then
        echo "==> Stopping order-management-service"
        stop_pid_tree "$launcher"
    else
        echo "==> order-management-service was already down; it will be started at the end"
    fi
    rm -f "$pid_file"
fi
# Catch application JVMs orphaned from an earlier run: they hold the engine and
# would keep writing to the directory being cleared.
pkill -TERM -f "com.emporia.ordermanagement.OrderManagementServiceApplication" 2>/dev/null || true
sleep 3

echo "==> Clearing exchange-core engine state"
rm -rf "$STORAGE_DIR"
echo "    removed ${STORAGE_DIR}"

# A queued-but-undelivered snapshot from before this reset would otherwise
# survive it (this table is Postgres-backed, not part of $STORAGE_DIR) and
# get drained by the background publisher after restart, overwriting a
# freshly seeded portfolio balance with exchange-core's stale pre-reset one.
echo "==> Clearing exchange-core portfolio outbox backlog"
dropped="$(execution_psql_q "delete from emporia_execution.exchange_core_portfolio_outbox returning 1;" | grep -c 1 || true)"
echo "    dropped ${dropped:-0} queued/dead outbox records"

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

# Unconditionally, not only when this script did the stopping. It used to skip
# the start when the process was already gone, so a service that had crashed
# left this script clearing the engine, printing "Reset complete" and exiting 0
# with nothing running - and every caller of this script goes straight on to
# send orders. A reset that ends with no venue is not a reset anyone wants.
echo "==> Starting order-management-service on a clean engine"
DB_URL="${DB_URL:-jdbc:postgresql://localhost:5436/emporia_order_management}" \
DB_PASSWORD="${DB_PASSWORD:-admin123}" \
EXECUTION_DB_URL="${EXECUTION_DB_URL:-jdbc:postgresql://localhost:5437/emporia_execution}" \
EXECUTION_DB_USERNAME="${EXECUTION_DB_USERNAME:-postgres}" \
EXECUTION_DB_PASSWORD="${EXECUTION_DB_PASSWORD:-admin123}" \
EXECUTION_VENUE_MODE="${EXECUTION_VENUE_MODE:-exchange-core}" \
EXCHANGE_CORE_ACCOUNTING_MODE="${EXCHANGE_CORE_ACCOUNTING_MODE:-full-equity-risk}" \
EXCHANGE_CORE_PORTFOLIO_URL="${EXCHANGE_CORE_PORTFOLIO_URL:-http://localhost:8088}" \
EXCHANGE_CORE_WAIT_STRATEGY="${EXCHANGE_CORE_WAIT_STRATEGY:-blocking}" \
EMPORIA_DISRUPTOR_STALL_THRESHOLD_MS="${EMPORIA_DISRUPTOR_STALL_THRESHOLD_MS:-0}" \
EMPORIA_DEDUP_INDEX_ENABLED="${EMPORIA_DEDUP_INDEX_ENABLED:-true}" \
    start_service order-management-service order-management-service mvn -DskipTests spring-boot:run
wait_http_health order-management-service http://localhost:8086/actuator/health \
    || fail "order-management-service did not come up; see $log_dir/order-management-service.log"
echo "    up on a clean engine"

echo "==> Reset complete: 0 working orders, empty engine state, service healthy"
