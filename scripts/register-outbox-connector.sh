#!/bin/bash
# Registers (or updates) the Debezium connector that CDC-drains order_outbox.
# PUT .../config is idempotent - creates the connector if absent, updates it
# in place if already registered - so re-running this on every startup is safe.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

CONNECT_URL="${CONNECT_URL:-http://localhost:18083}"
CONNECTOR_NAME="${CONNECTOR_NAME:-order-outbox-connector}"
CONNECTOR_CONFIG="${CONNECTOR_CONFIG:-$repo_root/deploy/debezium/order-outbox-connector.json}"
CONNECTOR_WAIT_SECONDS="${CONNECTOR_WAIT_SECONDS:-60}"

config_to_send="$CONNECTOR_CONFIG"
tmp_config=""

cleanup() {
    [ -z "$tmp_config" ] || rm -f "$tmp_config"
}
trap cleanup EXIT

if [ -n "${DB_HOST:-}${DB_PORT:-}${DB_USERNAME:-}${DB_PASSWORD:-}${DB_NAME:-}" ]; then
    tmp_config="$(mktemp)"
    python3 - "$CONNECTOR_CONFIG" "$tmp_config" <<'PY'
import json
import os
import sys

source, target = sys.argv[1], sys.argv[2]
with open(source, encoding="utf-8") as handle:
    config = json.load(handle)

overrides = {
    "DB_HOST": "database.hostname",
    "DB_PORT": "database.port",
    "DB_USERNAME": "database.user",
    "DB_PASSWORD": "database.password",
    "DB_NAME": "database.dbname",
}
for env_name, config_key in overrides.items():
    value = os.environ.get(env_name)
    if value:
        config[config_key] = value

with open(target, "w", encoding="utf-8") as handle:
    json.dump(config, handle, indent=2)
    handle.write("\n")
PY
    config_to_send="$tmp_config"
fi

curl -fsS -X PUT "$CONNECT_URL/connectors/$CONNECTOR_NAME/config" \
    -H "Content-Type: application/json" \
    -d @"$config_to_send" \
    -o /dev/null

if [ "$CONNECTOR_WAIT_SECONDS" -le 0 ]; then
    exit 0
fi

deadline=$((SECONDS + CONNECTOR_WAIT_SECONDS))
status_url="$CONNECT_URL/connectors/$CONNECTOR_NAME/status"
printf '    waiting for %s connector' "$CONNECTOR_NAME"
while [ "$SECONDS" -lt "$deadline" ]; do
    status="$(curl -fsS "$status_url" 2>/dev/null || true)"
    if [ -n "$status" ] && echo "$status" | grep -q '"state":"FAILED"'; then
        echo
        echo "    $CONNECTOR_NAME failed to start: $status" >&2
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
echo "    $CONNECTOR_NAME did not report RUNNING within ${CONNECTOR_WAIT_SECONDS}s" >&2
exit 1
