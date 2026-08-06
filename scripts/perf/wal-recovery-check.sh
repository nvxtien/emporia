#!/bin/bash
# Proves order-management recovers orders that were accepted but not yet written
# when the process died.
#
# Usage: scripts/perf/wal-recovery-check.sh
#
# The hot path acknowledges an order once the ring has applied it, while the row
# reaches Postgres later - AsyncDbWriter flushes every 10ms or every 500 rows.
# Anything inside that window exists only in the write-ahead log, so a kill -9
# there is the one event that says whether the log is doing its job.
#
# Deliberately kill -9 rather than a graceful stop: shutdown flushes the queues,
# which is exactly the window being tested.
#
# The window is small, so this submits a burst and kills immediately: at any
# moment during a burst the queues hold recent orders. It then checks that every
# order the API accepted is in the database after the restart, and that the
# recovery actually replayed something rather than the burst happening to land
# entirely on flush boundaries.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8082}"
OMS_URL="${OMS_URL:-http://localhost:8086}"
ORIGIN="${EMPORIA_ORIGIN:-http://localhost:3001}"
PG_CONTAINER="${PG_CONTAINER:-emporia-order-management-postgres}"
PG_DB="${PG_DB:-emporia_order_management}"
PG_USER="${PG_USER:-postgres}"
BURST="${WAL_BURST:-150}"
log_file="$repo_root/.local-run/logs/order-management-service.log"
pid_file="$repo_root/.local-run/pids/order-management-service.pid"

fail() { echo "FAIL: $*" >&2; exit 1; }
psql_q() { docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc "$1" 2>/dev/null; }

curl -fsS "${OMS_URL}/actuator/health/liveness" >/dev/null 2>&1 \
    || fail "order-management is not up at ${OMS_URL}"

token="$(EMPORIA_ORIGIN="$ORIGIN" EMPORIA_USERNAME="${EMPORIA_USERNAME:-admin}" \
    EMPORIA_PASSWORD="${EMPORIA_PASSWORD:-admin123}" \
    node "$repo_root/scripts/perf/get-access-token.mjs" 2>/dev/null)" \
    || fail "could not mint an access token"

echo "==> Submitting concurrently, then killing while orders are still arriving"
accepted_file="$(mktemp)"
# Submitted in parallel and killed mid-flight, because the window being tested
# is the ~10ms before a flush. One order at a time is slower than that, so each
# is safely in the database before the next arrives and the kill lands on an
# empty queue - a pass that proves nothing.
submit_stream() {
    local worker="$1"
    for i in $(seq 1 "$BURST"); do
        curl -fsS --max-time 5 -X POST "${GATEWAY_URL}/api/orders" \
            -H "Authorization: Bearer ${token}" \
            -H "Content-Type: application/json" \
            -H "Idempotency-Key: wal-recovery-${worker}-${i}-$(date +%s%N)" \
            -d '{"listingId":1,"side":"BUY","type":"LIMIT","quantity":"1",
                 "limitPrice":"100.00","destination":"DMA"}' 2>/dev/null \
            | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])' >> "$accepted_file" 2>/dev/null || true
    done
}
for worker in $(seq 1 "${WAL_WORKERS:-12}"); do submit_stream "$worker" & done
sleep "${WAL_KILL_AFTER:-3}"

# Kill without warning, with the queues still holding recent orders.
launcher="$(cat "$pid_file" 2>/dev/null)" || fail "no order-management pid file"
child="$(pgrep -P "$launcher" 2>/dev/null | head -1)"
[ -n "$child" ] || fail "could not find the application JVM under launcher ${launcher}"
kill -9 "$child" "$launcher" 2>/dev/null || true
while kill -0 "$child" 2>/dev/null; do sleep 1; done
echo "    killed jvm ${child}"
wait 2>/dev/null || true
accepted="$(grep -c . "$accepted_file" || true)"
echo "    the API accepted ${accepted} orders before the kill"
[ "${accepted:-0}" -gt 0 ] || fail "no orders were accepted; nothing to recover"

missing_before="$(psql_q "select count(*) from (select unnest(string_to_array('$(paste -sd, "$accepted_file")', ','))::uuid as id) a
    where not exists (select 1 from emporia_order_data.trading_order t where t.id = a.id);")"
echo "    orders not yet in the database at the moment of the kill: ${missing_before:-0}"

echo "==> Restarting"
( cd "$repo_root/order-management-service" && exec env \
    DB_URL="${DB_URL:-jdbc:postgresql://localhost:5436/emporia_order_management}" \
    DB_PASSWORD="${DB_PASSWORD:-admin123}" \
    mvn spring-boot:run ) > "$log_file" 2>&1 &
echo "$!" > "$pid_file"

printf "    waiting"
for _ in $(seq 1 80); do
    curl -fsS "${OMS_URL}/actuator/health/liveness" >/dev/null 2>&1 && break
    printf '.'; sleep 3
done
echo
curl -fsS "${OMS_URL}/actuator/health/liveness" >/dev/null 2>&1 \
    || fail "order-management did not come back; see ${log_file}"

echo "==> Verifying recovery"
replayed="$(grep -oE "Replaying [0-9]+ order command" "$log_file" | grep -oE "[0-9]+" | tail -1 || true)"
echo "    replayed from the write-ahead log: ${replayed:-0}"

sleep 3
missing_after="$(psql_q "select count(*) from (select unnest(string_to_array('$(paste -sd, "$accepted_file")', ','))::uuid as id) a
    where not exists (select 1 from emporia_order_data.trading_order t where t.id = a.id);")"

[ "${missing_after:-1}" = "0" ] || fail "${missing_after} order(s) the API accepted are absent after recovery.
  Those were acknowledged to the caller and then lost, which is the failure this
  log exists to prevent. Look for 'Replaying' in ${log_file}."

if [ "${missing_before:-0}" = "0" ]; then
    echo "==> PASS, but weakly: every order had already been flushed when the kill"
    echo "    landed, so nothing needed recovering. Re-run, or raise WAL_BURST,"
    echo "    to exercise the window properly."
else
    echo "==> PASS: ${missing_before} order(s) were unflushed at the kill and all"
    echo "    are present after recovery"
fi
rm -f "$accepted_file"
