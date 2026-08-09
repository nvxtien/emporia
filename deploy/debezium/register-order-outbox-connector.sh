#!/bin/sh
# Register the order_outbox Debezium connector from the full-Docker stack.
set -eu

connect_url="${CONNECT_URL:-http://kafka-connect:8083}"
connector_name="${CONNECTOR_NAME:-order-outbox-connector}"
connector_config="${CONNECTOR_CONFIG:-/connector/order-outbox-connector.json}"
wait_seconds="${CONNECTOR_WAIT_SECONDS:-60}"

curl -fsS -X PUT "$connect_url/connectors/$connector_name/config" \
    -H "Content-Type: application/json" \
    -d @"$connector_config" \
    -o /dev/null

if [ "$wait_seconds" -le 0 ]; then
    exit 0
fi

deadline=$(( $(date +%s) + wait_seconds ))
status_url="$connect_url/connectors/$connector_name/status"
printf 'waiting for %s connector' "$connector_name"
while [ "$(date +%s)" -lt "$deadline" ]; do
    status="$(curl -fsS "$status_url" 2>/dev/null || true)"
    if [ -n "$status" ] && echo "$status" | grep -q '"state":"FAILED"'; then
        echo
        echo "$connector_name failed to start: $status" >&2
        exit 1
    fi
    if [ -n "$status" ] \
            && echo "$status" | grep -q '"connector":{"state":"RUNNING"' \
            && echo "$status" | grep -q '"tasks".*"state":"RUNNING"' \
            && ! echo "$status" | grep -q '"state":"FAILED"'; then
        echo " up"
        exit 0
    fi
    printf '.'
    sleep 2
done

echo
echo "$connector_name did not report RUNNING within ${wait_seconds}s" >&2
exit 1
