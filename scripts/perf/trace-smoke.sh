#!/bin/bash
# REWORK_NOTE Phase 1_1, step 8: submit an order and confirm one connected
# trace reaches Tempo for it.
#
# Execution routing runs in-process inside order-management-service now, so
# there is no longer a cross-service hop on the order path to lose trace
# context across. What can still silently break: order submission dispatches
# to ShardedOrderDispatcher's async shard-executor thread, and trace context
# does not cross an executor boundary for free - a regression there would
# still produce perfectly good local spans while the order's trace loses the
# work done on the shard thread. Only an end-to-end assertion catches that.
#
# Usage: scripts/perf/trace-smoke.sh
#
# Requires a running stack (scripts/run-local.sh or scripts/run-infra-docker.sh)
# plus the observability containers.
set -e

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

# Login must use the frontend origin because that is the registered OIDC
# redirect URI, but the order itself is submitted to the gateway. Going through
# the Vite dev proxy instead loses the downstream trace: the order is created
# and the metric increments, yet no span reaches the collector. The gateway is
# the real API boundary, so this is both the working and the more accurate path.
ORIGIN="${EMPORIA_ORIGIN:-http://localhost:3001}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8082}"
TEMPO_URL="${TEMPO_URL:-http://localhost:3200}"
LISTING_ID="${SMOKE_LISTING_ID:-1}"

# Services that must appear in the trace for propagation to be considered
# working. The gateway is deliberately not required here: its application.yml
# has no management.opentelemetry.tracing.export.otlp.endpoint configured, so
# it never exports spans at all - a real, pre-existing gap, unrelated to this
# check. order-management-service is the only service that can appear now
# that execution routing runs in-process inside it.
REQUIRED_SERVICES=(order-management-service)

fail() { echo "FAIL: $*" >&2; exit 1; }

# The authorization server permits only authorization-code + PKCE for
# emporia-web, so the token comes from the shared helper rather than a direct
# grant. Requests go through the frontend origin, which proxies to the gateway,
# matching how a browser actually reaches the API.
echo "==> Obtaining an access token"
if [ -n "${SMOKE_ACCESS_TOKEN:-}" ]; then
    access_token="$SMOKE_ACCESS_TOKEN"
else
    access_token="$(EMPORIA_ORIGIN="$ORIGIN" \
        EMPORIA_USERNAME="${EMPORIA_USERNAME:-admin}" \
        EMPORIA_PASSWORD="${EMPORIA_PASSWORD:-admin123}" \
        node "$repo_root/scripts/perf/get-access-token.mjs")" \
        || fail "could not obtain an access token (is the stack up on ${ORIGIN}?)"
fi
[ -n "$access_token" ] || fail "no access token"

echo "==> Submitting an order"
submitted_at=$(date +%s)
order_response="$(curl -fsS -X POST "${GATEWAY_URL}/api/orders" \
    -H "Authorization: Bearer ${access_token}" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: trace-smoke-$(date +%s)-$$" \
    -d "{\"listingId\":${LISTING_ID},\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":10,\"limitPrice\":100.00,\"destination\":\"DMA\"}")" \
    || fail "order submission failed"

order_id="$(printf '%s' "$order_response" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))')"
[ -n "$order_id" ] || fail "no order id in response: $order_response"
echo "    order ${order_id}"

# Tempo needs a moment to ingest, and execution spans land after the HTTP
# response since order routing is dispatched to a background shard thread
# rather than completed inline before the response is returned.
echo "==> Waiting for spans to reach Tempo"
trace_id=""
trace=""
# Query the order_id span attribute directly rather than searching by span name
# and scanning the results.
#
# Searching for name="emporia.order.submit" returns a capped page of results,
# and after any load run there are hundreds of submit traces in the window, so
# the trace just created may never appear in that page no matter how long the
# script waits. That produced a confident-looking failure on a system that was
# working perfectly. Matching on the attribute returns exactly one trace
# regardless of how many others exist.
#
# order_id is a high-cardinality (span-only) attribute, so it identifies the
# trace without appearing as a metric tag - verified: emporia_order_submit
# exposes only 2 series.
for _ in $(seq 1 60); do
    sleep 3
    # Tempo's search needs an explicit window; without start/end it applies a
    # narrow default and misses a trace that has only just been ingested.
    search_start=$((submitted_at - 60))
    search_end=$(( $(date +%s) + 60 ))
    trace_id="$(curl -fsS --get "${TEMPO_URL}/api/search" \
        --data-urlencode "q={ span.order_id=\"${order_id}\" }" \
        --data-urlencode "start=${search_start}" \
        --data-urlencode "end=${search_end}" \
        --data-urlencode 'limit=10' 2>/dev/null \
        | python3 -c '
import json, sys
try:
    traces = json.load(sys.stdin).get("traces") or []
except Exception:
    traces = []
for trace in traces:
    found = trace.get("traceID") or trace.get("traceId") or trace.get("trace_id") or ""
    if found:
        print(found)
        break
')" || true
    [ -n "$trace_id" ] && break
done
[ -n "$trace_id" ] || fail "no trace with order_id ${order_id} appeared in Tempo"
echo "    trace ${trace_id}"

echo "==> Inspecting the trace"
[ -n "$trace" ] || trace="$(curl -fsS "${TEMPO_URL}/api/traces/${trace_id}")" || fail "could not fetch trace"

services="$(printf '%s' "$trace" | python3 -c '
import json, sys
data = json.load(sys.stdin)
found = set()
# Tempo returns either OTLP batches or the Jaeger-style shape depending on
# version; handle both rather than guessing.
for batch in data.get("batches", []):
    for attribute in batch.get("resource", {}).get("attributes", []):
        if attribute.get("key") == "service.name":
            found.add(attribute.get("value", {}).get("stringValue", ""))
for process in (data.get("data") or [{}])[0].get("processes", {}).values():
    if process.get("serviceName"):
        found.add(process["serviceName"])
print("\n".join(sorted(f for f in found if f)))
')"

echo "    services in trace:"
printf '%s\n' "$services" | sed 's/^/      /'

missing=()
for service in "${REQUIRED_SERVICES[@]}"; do
    printf '%s\n' "$services" | grep -qx "$service" || missing+=("$service")
done

if [ ${#missing[@]} -gt 0 ]; then
    echo >&2
    echo "FAIL: the order did not produce one connected trace." >&2
    echo "Missing spans from: ${missing[*]}" >&2
    echo >&2
    echo "The most common cause is the wrong OTLP endpoint property. Boot 4" >&2
    echo "deprecated management.otlp.tracing.endpoint at level=error, so it is" >&2
    echo "no longer bound and is silently ignored. The live name is:" >&2
    echo "   management.opentelemetry.tracing.export.otlp.endpoint" >&2
    exit 1
fi

echo "==> Trace smoke test passed: ${#REQUIRED_SERVICES[@]} services in one trace"
