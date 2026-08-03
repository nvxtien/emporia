#!/bin/bash
# Regression check for the first-request 504.
#
# order-command-service submits orders via synchronous request/reply over Kafka,
# and its reply listener joins a brand-new consumer group on every start. With
# auto-offset-reset=latest, a reply written before that join completes is
# positioned past and skipped permanently, so an order that was created and
# persisted normally returns 504 to the caller ~8 seconds later. Measured window:
# about 2.7 seconds.
#
# The failure exists only in the first seconds of a process's life, so nothing
# else in the suite would ever notice a regression. This restarts the service and
# submits at the earliest possible moment.
#
# Pass = 201 (ready in time) or 503 (honestly not ready yet, safely retryable).
# Fail = 504, which means an order was created that the caller was told failed.
#
# Usage: scripts/perf/first-request-check.sh
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

ORIGIN="${EMPORIA_ORIGIN:-http://localhost:3001}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8082}"
SERVICE_URL="${SERVICE_URL:-http://localhost:8085}"
LISTING_ID="${SMOKE_LISTING_ID:-1}"
log_dir="$repo_root/.local-run/logs"
pid_file="$repo_root/.local-run/pids/order-command-service.pid"

fail() { echo "FAIL: $*" >&2; exit 1; }

[ -f "$pid_file" ] || fail "no pid file; this check needs the host-JVM run mode (scripts/run-infra-docker.sh)"

echo "==> Obtaining an access token (before the restart, so timing is not skewed)"
access_token="$(EMPORIA_ORIGIN="$ORIGIN" \
    EMPORIA_USERNAME="${EMPORIA_USERNAME:-admin}" \
    EMPORIA_PASSWORD="${EMPORIA_PASSWORD:-admin123}" \
    node "$repo_root/scripts/perf/get-access-token.mjs" 2>/dev/null)" \
    || fail "could not obtain an access token (is the stack up on ${ORIGIN}?)"
[ -n "$access_token" ] || fail "no access token"

echo "==> Restarting order-command-service"
launcher="$(cat "$pid_file")"
# The pid file records the `mvn spring-boot:run` launcher, which forks the
# application into a child JVM; both must go.
child="$(pgrep -P "$launcher" | head -1)"
[ -n "$child" ] && kill -TERM "$child" 2>/dev/null
kill -TERM "$launcher" 2>/dev/null
while kill -0 "$launcher" 2>/dev/null; do sleep 1; done
sleep 2

mkdir -p "$log_dir"
( cd "$repo_root/order-command-service" && exec mvn spring-boot:run ) \
    >"$log_dir/order-command-service.log" 2>&1 &
echo "$!" >"$pid_file"
echo "    launcher pid $(cat "$pid_file")"

# Must wait on the liveness probe specifically, NOT on /actuator/health.
#
# The replyListener health indicator contributes to the aggregate health
# endpoint, so /actuator/health now returns 503 for the entire window this check
# exists to probe. Waiting on it would skip the window completely and the check
# could never fail. Measured on a restart:
#
#   t+0.8s   health=503  liveness=200  readiness=503   <- the window
#   t+3.2s   health=200  liveness=200  readiness=200
#
# liveness is the only probe that is up during the gap.
echo "==> Waiting for the liveness probe (deliberately not readiness or health)"
waited=0
until curl -fsS "${SERVICE_URL}/actuator/health/liveness" >/dev/null 2>&1; do
    sleep 0.2
    waited=$((waited + 1))
    [ "$waited" -gt 900 ] && fail "order-command-service did not come up within 180s"
done

echo "==> Submitting immediately"
status="$(curl -s -o /tmp/first-request-check.json -w '%{http_code}' --max-time 30 \
    -X POST "${GATEWAY_URL}/api/orders" \
    -H "Authorization: Bearer ${access_token}" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: first-request-check-$(date +%s)-$$" \
    -d "{\"listingId\":${LISTING_ID},\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":10,\"limitPrice\":100.00,\"destination\":\"DMA\"}")"

echo "    HTTP ${status}"
case "$status" in
    201)
        echo "==> PASS: the reply listener was ready and the order was acknowledged"
        ;;
    503)
        echo "==> PASS: the service correctly refused before the reply listener was ready"
        echo "    503 is safe: nothing was published, so no order was created."
        ;;
    504)
        echo >&2
        echo "FAIL: got 504 - the first-request race has regressed." >&2
        echo >&2
        echo "A 504 here means the command was published before the reply listener" >&2
        echo "owned its partitions. The order was almost certainly created and" >&2
        echo "persisted; only the reply was lost. Check that KafkaCommandGateway" >&2
        echo "still refuses to publish while ReplyListenerReadiness reports not" >&2
        echo "ready, and that its ConsumerSeekAware callbacks are still wired." >&2
        exit 1
        ;;
    *)
        fail "unexpected HTTP ${status}: $(head -c 200 /tmp/first-request-check.json)"
        ;;
esac

echo "==> Waiting for readiness before handing the stack back"
waited=0
until curl -fsS "${SERVICE_URL}/actuator/health/readiness" >/dev/null 2>&1; do
    sleep 0.5
    waited=$((waited + 1))
    [ "$waited" -gt 120 ] && fail "readiness probe never came up"
done
echo "    ready"
