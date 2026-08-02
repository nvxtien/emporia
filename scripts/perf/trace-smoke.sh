#!/bin/bash
# REWORK_NOTE Phase 1_1, step 8: submit an order and confirm the resulting trace
# spans order-command, order-management, and execution.
#
# This is the check that proves trace context propagates across BOTH HTTP and
# Kafka. Kafka observations are off by default in Spring Boot; without them each
# service still produces perfectly good local spans, so everything looks healthy
# while the order actually fragments into several disconnected traces. Only an
# end-to-end assertion catches that.
#
# Usage: scripts/perf/trace-smoke.sh
#
# Requires a running stack (scripts/run-local.sh or scripts/run-infra-docker.sh)
# plus the observability containers.
set -e

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8082}"
AUTH_URL="${AUTH_URL:-http://localhost:9000}"
TEMPO_URL="${TEMPO_URL:-http://localhost:3200}"
USERNAME="${SMOKE_USERNAME:-admin}"
PASSWORD="${SMOKE_PASSWORD:-admin123}"
LISTING_ID="${SMOKE_LISTING_ID:-1}"

# Services that must appear in the trace for propagation to be considered working.
REQUIRED_SERVICES=(order-command-service order-management-service execution-service)

fail() { echo "FAIL: $*" >&2; exit 1; }

echo "==> Obtaining an access token"
token_response="$(curl -fsS -u "emporia-web:" \
    -d "grant_type=password&username=${USERNAME}&password=${PASSWORD}&scope=openid" \
    "${AUTH_URL}/oauth2/token" 2>/dev/null)" || {
        echo "Could not obtain a token from ${AUTH_URL}." >&2
        echo "The bootstrap admin uses the authorization-code flow, so this script" >&2
        echo "needs a client that permits a direct grant, or an existing token in" >&2
        echo "SMOKE_ACCESS_TOKEN." >&2
        [ -n "${SMOKE_ACCESS_TOKEN:-}" ] || exit 1
    }

access_token="${SMOKE_ACCESS_TOKEN:-$(printf '%s' "$token_response" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')}"
[ -n "$access_token" ] || fail "no access token"

echo "==> Submitting an order"
order_response="$(curl -fsS -X POST "${GATEWAY_URL}/api/orders" \
    -H "Authorization: Bearer ${access_token}" \
    -H "Content-Type: application/json" \
    -d "{\"listingId\":${LISTING_ID},\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":10,\"limitPrice\":100.00,\"destination\":\"DMA\"}")" \
    || fail "order submission failed"

order_id="$(printf '%s' "$order_response" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))')"
[ -n "$order_id" ] || fail "no order id in response: $order_response"
echo "    order ${order_id}"

# Tempo needs a moment to ingest, and execution spans land after the HTTP
# response since they arrive via Kafka.
echo "==> Waiting for spans to reach Tempo"
trace_id=""
trace=""
for _ in $(seq 1 30); do
    sleep 2
    search="$(curl -fsS --get "${TEMPO_URL}/api/search" \
        --data-urlencode 'q={ name="emporia.order.submit" }' \
        --data-urlencode 'limit=100' 2>/dev/null)" || continue
    candidate_trace_ids="$(printf '%s' "$search" | python3 -c '
import json, sys
try:
    traces = json.load(sys.stdin).get("traces") or []
except Exception:
    traces = []
for trace in traces:
    trace_id = trace.get("traceID") or trace.get("traceId") or trace.get("trace_id") or ""
    if trace_id:
        print(trace_id)
')"
    while IFS= read -r candidate_trace_id; do
        [ -n "$candidate_trace_id" ] || continue
        candidate_trace="$(curl -fsS "${TEMPO_URL}/api/traces/${candidate_trace_id}" 2>/dev/null)" || continue
        contains_order="$(printf '%s' "$candidate_trace" | python3 - "$order_id" <<'PY'
import json
import sys

order_id = sys.argv[1]
order_keys = {"order_id", "order.id"}

def scalar(value):
    if isinstance(value, dict):
        for key in ("stringValue", "intValue", "doubleValue", "boolValue"):
            if key in value:
                return str(value[key])
        return ""
    return "" if value is None else str(value)

def attributes_match(attributes):
    for attribute in attributes or []:
        if not isinstance(attribute, dict) or attribute.get("key") not in order_keys:
            continue
        if scalar(attribute.get("value")) == order_id:
            return True
    return False

def walk(node):
    if isinstance(node, dict):
        if attributes_match(node.get("attributes")) or attributes_match(node.get("tags")):
            return True
        return any(walk(value) for value in node.values())
    if isinstance(node, list):
        return any(walk(value) for value in node)
    return False

try:
    data = json.load(sys.stdin)
except Exception:
    data = {}
print("yes" if walk(data) else "no")
PY
)"
        if [ "$contains_order" = "yes" ]; then
            trace_id="$candidate_trace_id"
            trace="$candidate_trace"
            break
        fi
    done <<EOF
$candidate_trace_ids
EOF
    [ -n "$trace_id" ] && break
done
[ -n "$trace_id" ] || fail "no trace containing emporia.order.submit for order ${order_id} appeared in Tempo"
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
    echo "Two causes account for almost every occurrence:" >&2
    echo >&2
    echo "  1. Kafka trace propagation disabled - both of these must be set in" >&2
    echo "     the affected services (they default to false):" >&2
    echo "        spring.kafka.template.observation-enabled: true" >&2
    echo "        spring.kafka.listener.observation-enabled: true" >&2
    echo >&2
    echo "  2. The wrong OTLP endpoint property. Boot 4 deprecated" >&2
    echo "     management.otlp.tracing.endpoint at level=error, so it is no" >&2
    echo "     longer bound and is silently ignored. The live name is:" >&2
    echo "        management.opentelemetry.tracing.export.otlp.endpoint" >&2
    exit 1
fi

echo "==> Trace smoke test passed: ${#REQUIRED_SERVICES[@]} services in one trace"
