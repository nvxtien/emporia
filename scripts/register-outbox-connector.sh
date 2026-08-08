#!/bin/bash
# Registers (or updates) the Debezium connector that CDC-drains order_outbox.
# PUT .../config is idempotent - creates the connector if absent, updates it
# in place if already registered - so re-running this on every startup is
# safe.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

curl -fsS -X PUT "http://localhost:8083/connectors/order-outbox-connector/config" \
    -H "Content-Type: application/json" \
    -d @"$repo_root/deploy/debezium/order-outbox-connector.json" \
    -o /dev/null
