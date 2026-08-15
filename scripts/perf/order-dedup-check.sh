#!/usr/bin/env bash
# Exercises the two order-path guards that no load test reaches, end to end
# through the gateway.
#
# order-load.js generates a fresh Idempotency-Key and a fresh order for every
# request, so a benchmark never sends a duplicate command and never sends a
# modify. Both of the guards below therefore ran only in unit tests, on code
# whose whole point is what it does against a real database:
#
#   1. A repeated Idempotency-Key returns the original order rather than
#      creating a second one. This is what the deduplication index answers from
#      memory - see CONFIGURATION.md - and answering it wrongly means a
#      duplicate position.
#   2. A modify carrying a version older than the order's is refused with 409.
#      That guard stopped firing when order writes moved to raw JDBC and nothing
#      advanced entity_version any more; it is back, and this proves it.
#
# Read-only apart from the two orders it creates.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8082}"
LISTING_ID="${LISTING_ID:-1}"

fail() { echo "FAIL: $*" >&2; exit 1; }

token="$(EMPORIA_ORIGIN="${EMPORIA_ORIGIN:-http://localhost:3001}" \
         EMPORIA_USERNAME="${EMPORIA_USERNAME:-admin}" \
         EMPORIA_PASSWORD="${EMPORIA_PASSWORD:-admin123}" \
         node scripts/perf/get-access-token.mjs 2>/dev/null)" || fail "could not mint a token"
case "$token" in ey*) ;; *) fail "token mint returned something that is not a JWT";; esac

# $1 = idempotency key. Prints "<http-status> <body>".
create() {
    curl -s -w '\n%{http_code}' -X POST "$GATEWAY_URL/api/orders" \
        -H "Authorization: Bearer $token" \
        -H "Content-Type: application/json" \
        -H "Idempotency-Key: $1" \
        -d "{\"listingId\":$LISTING_ID,\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":\"10\",\"limitPrice\":\"100.00\",\"destination\":\"DMA\"}"
}

# $1 = order id, $2 = expectedVersion, $3 = idempotency key.
modify() {
    curl -s -o /dev/null -w '%{http_code}' -X PUT "$GATEWAY_URL/api/orders/$1" \
        -H "Authorization: Bearer $token" \
        -H "Content-Type: application/json" \
        -H "Idempotency-Key: $3" \
        -d "{\"expectedVersion\":$2,\"quantity\":\"12\",\"limitPrice\":\"101.00\"}"
}

status_of() { printf '%s' "$1" | tail -1; }
body_of()   { printf '%s' "$1" | sed '$d'; }

echo "==> 1. a repeated Idempotency-Key must not create a second order"
key="dedup-check-$(date +%s)-$RANDOM"
first="$(create "$key")"
[ "$(status_of "$first")" = "201" ] || fail "first create returned $(status_of "$first"), not 201"
first_id="$(body_of "$first" | jq -r '.id')"

second="$(create "$key")"
second_status="$(status_of "$second")"
second_id="$(body_of "$second" | jq -r '.id')"
# The replay is answered from the recorded result, so it repeats the original
# status as well as the original order.
[ "$second_status" = "201" ] || fail "replay returned $second_status, not the recorded 201"
[ "$second_id" = "$first_id" ] \
    || fail "replay created a second order: $first_id then $second_id"
echo "    both calls returned $first_id"

echo "==> 2. a modify carrying a stale version must be refused"
fresh="$(create "version-check-$(date +%s)-$RANDOM")"
[ "$(status_of "$fresh")" = "201" ] || fail "create returned $(status_of "$fresh"), not 201"
order_id="$(body_of "$fresh" | jq -r '.id')"
stale_version="$(body_of "$fresh" | jq -r '.version')"
[ "$stale_version" != "null" ] || fail "create response carried no version"

accepted="$(modify "$order_id" "$stale_version" "modify-a-$(date +%s)-$RANDOM")"
[ "$accepted" = "200" ] || fail "modify with the current version returned $accepted, not 200"
echo "    modify at version $stale_version accepted"

# The accepted modify advanced the revision, so the same expectedVersion is now
# a stale read. Before entity_version was fixed this returned 200: the version
# never moved, so the guard compared 0 against 0 and passed every time.
refused="$(modify "$order_id" "$stale_version" "modify-b-$(date +%s)-$RANDOM")"
[ "$refused" = "409" ] || fail "modify replayed at version $stale_version returned $refused, not 409"
echo "    modify replayed at version $stale_version refused with 409"

echo "==> both guards hold"
