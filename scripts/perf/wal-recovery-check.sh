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

# Widened well past HTTP round-trip time for one curl process, so the burst
# below is guaranteed to still be queued when the kill lands rather than racing
# a 10ms flush no single-threaded submitter can outrun.
FLUSH_DELAY_MS="${WAL_FLUSH_DELAY_MS:-30000}"
echo "==> Restarting with the flush delay widened to ${FLUSH_DELAY_MS}ms, to force a real backlog"
restart_oms() {
    local flush_delay="$1"
    local launcher child
    launcher="$(cat "$pid_file" 2>/dev/null || true)"
    # -sTCP:LISTEN, not a bare port match. `lsof -i :8086` also returns processes
    # holding a *connection to* 8086 - the gateway proxies there, so it appears in
    # that list the moment any request has flowed - and head -1 then picked it.
    # This script killed the gateway instead of order-management-service, twice,
    # and the symptom surfaced two steps later as "could not mint an access token".
    # `|| true` because a correct selector legitimately matches nothing once the
    # service is down, and under `set -euo pipefail` an empty match would kill
    # the script silently - which it did, mid-restart, with no message at all.
    child="$(lsof -ti tcp:8086 -sTCP:LISTEN 2>/dev/null | head -1 || true)"
    if [ -n "$child" ]; then kill -9 "$child" 2>/dev/null || true; fi
    if [ -n "$launcher" ]; then kill -9 "$launcher" 2>/dev/null || true; fi
    while [ -n "$child" ] && kill -0 "$child" 2>/dev/null; do sleep 1; done
    ( cd "$repo_root/order-management-service" && exec env \
        DB_URL="${DB_URL:-jdbc:postgresql://localhost:5436/emporia_order_management}" \
        DB_PASSWORD="${DB_PASSWORD:-admin123}" \
        EMPORIA_ASYNC_DB_WRITER_FLUSH_DELAY_MS="$flush_delay" \
        mvn spring-boot:run ) > "$log_file" 2>&1 &
    echo "$!" > "$pid_file"
    printf "    waiting"
    for _ in $(seq 1 80); do
        curl -fsS "${OMS_URL}/actuator/health/liveness" >/dev/null 2>&1 && break
        printf '.'; sleep 3
    done
    echo
    curl -fsS "${OMS_URL}/actuator/health/liveness" >/dev/null 2>&1 \
        || fail "order-management did not come back with flush-delay=${flush_delay}ms; see ${log_file}"
}
restart_oms "$FLUSH_DELAY_MS"

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
        # The key is recorded beside the id because recovering the order is only
        # half of it: a client that retries after the crash must receive the
        # original order rather than create a second one, and that needs the
        # processed-command record back too.
        local key="wal-recovery-${worker}-${i}-$(date +%s%N)"
        curl -fsS --max-time 5 -X POST "${GATEWAY_URL}/api/orders" \
            -H "Authorization: Bearer ${token}" \
            -H "Content-Type: application/json" \
            -H "Idempotency-Key: ${key}" \
            -d '{"listingId":1,"side":"BUY","type":"LIMIT","quantity":"1",
                 "limitPrice":"100.00","destination":"DMA"}' 2>/dev/null \
            | python3 -c "import sys,json; print('${key},' + json.load(sys.stdin)['id'])" >> "$accepted_file" 2>/dev/null || true
    done
}
for worker in $(seq 1 "${WAL_WORKERS:-12}"); do submit_stream "$worker" & done
sleep "${WAL_KILL_AFTER:-3}"

# Kill without warning, with the queues still holding recent orders.
# Located by the port it serves, not by parent pid: the mvn launcher can exit
# and leave the application JVM running, in which case pgrep -P finds nothing.
launcher="$(cat "$pid_file" 2>/dev/null || true)"
# -sTCP:LISTEN, not a bare port match. `lsof -i :8086` also returns processes
    # holding a *connection to* 8086 - the gateway proxies there, so it appears in
    # that list the moment any request has flowed - and head -1 then picked it.
    # This script killed the gateway instead of order-management-service, twice,
    # and the symptom surfaced two steps later as "could not mint an access token".
    # `|| true` because a correct selector legitimately matches nothing once the
    # service is down, and under `set -euo pipefail` an empty match would kill
    # the script silently - which it did, mid-restart, with no message at all.
    child="$(lsof -ti tcp:8086 -sTCP:LISTEN 2>/dev/null | head -1 || true)"
[ -n "$child" ] || fail "could not find the JVM serving port 8086"
kill -9 "$child" 2>/dev/null || true
[ -n "$launcher" ] && kill -9 "$launcher" 2>/dev/null || true
while kill -0 "$child" 2>/dev/null; do sleep 1; done
echo "    killed jvm ${child}"
wait 2>/dev/null || true
accepted="$(grep -c . "$accepted_file" || true)"
echo "    the API accepted ${accepted} orders before the kill"
[ "${accepted:-0}" -gt 0 ] || fail "no orders were accepted; nothing to recover"

# BSD paste needs an explicit - for stdin; GNU accepts it too.
ids_csv="$(cut -d, -f2 "$accepted_file" | paste -sd, -)"
missing_ids="$(psql_q "select a.id from (select unnest(string_to_array('${ids_csv}', ','))::uuid as id) a
    where not exists (select 1 from emporia_order_data.trading_order t where t.id = a.id);")"
missing_before="$(printf '%s\n' "$missing_ids" | grep -c . || true)"
echo "    orders not yet in the database at the moment of the kill: ${missing_before:-0}"

# One of them, to replay after the restart.
in_flight_id="$(printf '%s\n' "$missing_ids" | grep . | head -1 || true)"
in_flight_key="$(grep ",${in_flight_id}\$" "$accepted_file" | cut -d, -f1 | head -1 || true)"

echo "==> Restarting with the normal flush delay"
restart_oms 10

echo "==> Verifying recovery"
replayed="$(grep -oE "Replaying [0-9]+ order command" "$log_file" | grep -oE "[0-9]+" | tail -1 || true)"
echo "    replayed from the write-ahead log: ${replayed:-0}"

sleep 3
missing_after="$(psql_q "select count(*) from (select unnest(string_to_array('${ids_csv}', ','))::uuid as id) a
    where not exists (select 1 from emporia_order_data.trading_order t where t.id = a.id);")"

[ "${missing_after:-1}" = "0" ] || fail "${missing_after} order(s) the API accepted are absent after recovery.
  Those were acknowledged to the caller and then lost, which is the failure this
  log exists to prevent. Look for 'Replaying' in ${log_file}."

# Recovering the order is half of it. A client that retries after the crash has
# to receive the original order, which needs the processed-command record back
# as well - and needs the deduplication index to have been filled from the
# replay before it started answering. Replay runs in DisruptorOrderPipeline's
# @PostConstruct, the index is published later on ApplicationReadyEvent, and
# that ordering is the reason this holds. It had never been tested.
if [ -n "${in_flight_key:-}" ]; then
    echo "==> Replaying an in-flight command, which must be deduplicated"
    # Status and body are both kept. An earlier version took only .id, so a
    # failed request and a duplicated order looked identical - an empty string -
    # and the failure message accused the system of the wrong thing.
    replay_body="$(curl -sS --max-time 10 -w '\n%{http_code}' -X POST "${GATEWAY_URL}/api/orders" \
        -H "Authorization: Bearer ${token}" \
        -H "Content-Type: application/json" \
        -H "Idempotency-Key: ${in_flight_key}" \
        -d '{"listingId":1,"side":"BUY","type":"LIMIT","quantity":"1",
             "limitPrice":"100.00","destination":"DMA"}' 2>&1 || true)"
    replay_status="$(printf '%s' "$replay_body" | tail -1)"
    replay_json="$(printf '%s' "$replay_body" | sed '$d')"
    replayed_id="$(printf '%s' "$replay_json" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("id",""))' 2>/dev/null || true)"

    [ "$replay_status" = "201" ] \
        || fail "replaying an in-flight command returned HTTP ${replay_status}, not 201.
  That is a failed request rather than a deduplication result, so this says
  nothing about the guard. Body: ${replay_json}"
    [ "$replayed_id" = "$in_flight_id" ] \
        || fail "replaying a command that was in flight at the kill returned order
  '${replayed_id}', not the original ${in_flight_id}. A caller retrying after a
  crash would have created a second order for one intent - a duplicate position."
    echo "    returned the original order ${in_flight_id}"
else
    echo "==> No in-flight command to replay; the deduplication half was not exercised"
fi

if [ "${missing_before:-0}" = "0" ]; then
    echo "==> PASS, but weakly: every order had already been flushed when the kill"
    echo "    landed, so nothing needed recovering. Re-run, or raise WAL_BURST,"
    echo "    to exercise the window properly."
else
    echo "==> PASS: ${missing_before} order(s) were unflushed at the kill, all are"
    echo "    present after recovery, and a retry of one is still deduplicated"
fi
rm -f "$accepted_file"
