#!/bin/bash
# Cold-start check for the OMS Disruptor hot path.
#
# Restarts order-management-service and immediately submits an order through the
# gateway. The retired order-command-service Kafka reply-listener race (504 after
# publish) no longer applies; this check now verifies that the gateway → OMS
# path accepts the first post-restart request without hanging.
#
# Pass = 201 Created (or another non-5xx success from OMS validation).
# Fail = 5xx / timeout, which means the hot path is not accepting work yet.
#
# Usage: scripts/perf/first-request-check.sh
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

ORIGIN="${EMPORIA_ORIGIN:-http://localhost:3001}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8082}"
SERVICE_URL="${SERVICE_URL:-http://localhost:8086}"
LISTING_ID="${SMOKE_LISTING_ID:-1}"
log_dir="$repo_root/.local-run/logs"
pid_file="$repo_root/.local-run/pids/order-management-service.pid"

fail() { echo "FAIL: $*" >&2; exit 1; }

[ -f "$pid_file" ] || fail "no pid file; this check needs the host-JVM run mode (scripts/run-local.sh)"

echo "==> Obtaining an access token (before the restart, so timing is not skewed)"
access_token="$(EMPORIA_ORIGIN="$ORIGIN" \
    EMPORIA_USERNAME="${EMPORIA_USERNAME:-admin}" \
    EMPORIA_PASSWORD="${EMPORIA_PASSWORD:-admin123}" \
    node "$repo_root/scripts/perf/get-access-token.mjs" 2>/dev/null)" \
    || fail "could not obtain an access token (is the stack up on ${ORIGIN}?)"
[ -n "$access_token" ] || fail "no access token"

echo "==> Restarting order-management-service"
launcher="$(cat "$pid_file")"
# The pid file records the `mvn spring-boot:run` launcher, which forks the
# application into a child JVM; both must go.
child="$(pgrep -P "$launcher" | head -1)"
[ -n "$child" ] && kill -TERM "$child" 2>/dev/null
kill -TERM "$launcher" 2>/dev/null
while kill -0 "$launcher" 2>/dev/null; do sleep 1; done
sleep 2

mkdir -p "$log_dir"
(
  cd "$repo_root/order-management-service" && \
  DB_USERNAME="${DB_USERNAME:-$(whoami)}" DB_PASSWORD="${DB_PASSWORD:-admin123}" \
  exec mvn -Dmatching spring-boot:run
) >"$log_dir/order-management-service.log" 2>&1 &
echo "$!" >"$pid_file"
echo "    launcher pid $(cat "$pid_file")"

echo "==> Waiting for OMS health"
waited=0
until curl -fsS "${SERVICE_URL}/actuator/health" >/dev/null 2>&1; do
    sleep 0.2
    waited=$((waited + 1))
    [ "$waited" -gt 900 ] && fail "order-management-service did not come up within 180s"
done

echo "==> Submitting immediately via gateway"
status="$(curl -s -o /tmp/first-request-check.json -w '%{http_code}' --max-time 30 \
    -X POST "${GATEWAY_URL}/api/orders" \
    -H "Authorization: Bearer ${access_token}" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: first-request-check-$(date +%s)-$$" \
    -d "{\"listingId\":${LISTING_ID},\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":10,\"limitPrice\":100.00,\"destination\":\"DMA\"}")"

echo "    HTTP ${status}"
case "$status" in
    201|200)
        echo "==> PASS: gateway → OMS Disruptor hot path accepted the first request"
        ;;
    5*)
        echo >&2
        echo "FAIL: got HTTP ${status} on the first post-restart submit." >&2
        echo "Check that DisruptorOrderPipeline started and OrderCommandController" >&2
        echo "is accepting work: $(head -c 200 /tmp/first-request-check.json)" >&2
        exit 1
        ;;
    *)
        fail "unexpected HTTP ${status}: $(head -c 200 /tmp/first-request-check.json)"
        ;;
esac
