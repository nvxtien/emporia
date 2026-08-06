#!/usr/bin/env bash
# ==============================================================================
# Helper Script: Run Order Submission Load Test
# Defaults to order-management-service hot path (:8086) & 5,000 total requests
# ==============================================================================

set -euo pipefail

export TARGET_URL="${TARGET_URL:-http://localhost:8086/orders}"
export TOTAL_REQUESTS="${TOTAL_REQUESTS:-5000}"
export CONCURRENCY="${CONCURRENCY:-10}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec "${SCRIPT_DIR}/order-submit-smoke.sh" "$@"
