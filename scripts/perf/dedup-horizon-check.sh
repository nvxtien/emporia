#!/usr/bin/env bash
# Demonstrates the deduplication horizon at runtime, and is the only thing that
# has ever made emporia.oms.dedup.duplicate_reached_db move off zero outside a
# unit test.
#
# The horizon is a correctness bound, not a performance one: past it the filters
# report "never seen" and OrderStateCache returns that answer without consulting
# PostgreSQL, so a repeated command is accepted as new. CONFIGURATION.md says so;
# this shows it.
#
# Two layers hide the bound from a short test, and both are compressed here.
# Production rotates at each trading-session start, so demonstrating a whole
# horizon against it would take more than a day; rotate-interval replaces the
# clock with a fixed spacing. Thirty-second sessions with two retained is a one-minute
# horizon, and an identifier is only dropped one rotation past it - ninety
# seconds. And OrderStateCache's Caffeine tier answers a repeat from its own
# memory before the filters are consulted at all, so it is shrunk to ten entries
# and then evicted.
#
# One Idempotency-Key is then sent three times - fresh, inside the horizon, past
# it - and the third must behave differently from the second.
#
# DESTRUCTIVE: restarts order-management-service with a compressed horizon and
# force-cancels working orders. Local use only. Restores nothing: rerun
# scripts/perf/reset-venue-state.sh --yes afterwards to return to the configured
# horizon.
set -uo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

export EMPORIA_DEDUP_INDEX_ROTATE_INTERVAL="${EMPORIA_DEDUP_INDEX_ROTATE_INTERVAL:-PT30S}"
export EMPORIA_DEDUP_INDEX_SESSIONS_RETAINED="${EMPORIA_DEDUP_INDEX_SESSIONS_RETAINED:-2}"
export EMPORIA_CACHE_PROCESSED_MAX_SIZE="${EMPORIA_CACHE_PROCESSED_MAX_SIZE:-10}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8082}"

fail() { echo "FAIL: $*" >&2; exit 1; }

echo "==> restarting with a compressed horizon"
bash scripts/perf/reset-venue-state.sh --yes >/tmp/dedup-horizon-reset.log 2>&1
sleep 18
curl -sf http://localhost:8086/actuator/health >/dev/null || fail "order-management-service is not healthy"

schedule="$(grep 'Deduplication index rotates' .local-run/logs/order-management-service.log | tail -1)"
echo "    $schedule"
# Trusting the environment reached the forked JVM would make a run that never
# rotates look like one that rotated and kept its answer.
case "$schedule" in
    *"FixedInterval[interval=PT30S]"*) ;;
    *) fail "the compressed horizon did not reach the JVM; it is still on the daily schedule";;
esac

token="$(EMPORIA_ORIGIN="${EMPORIA_ORIGIN:-http://localhost:3001}" \
         EMPORIA_USERNAME="${EMPORIA_USERNAME:-admin}" \
         EMPORIA_PASSWORD="${EMPORIA_PASSWORD:-admin123}" \
         node scripts/perf/get-access-token.mjs 2>/dev/null)" || fail "could not mint a token"
case "$token" in ey*) ;; *) fail "token mint returned something that is not a JWT";; esac

post() {
    curl -s -X POST "$GATEWAY_URL/api/orders" \
        -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
        -H "Idempotency-Key: $1" \
        -d '{"listingId":1,"side":"BUY","type":"LIMIT","quantity":"10","limitPrice":"100.00","destination":"DMA"}'
}
duplicates() {
    curl -s http://localhost:8086/actuator/prometheus \
        | awk '/^emporia_oms_dedup_duplicate_reached_db_total/{print $NF}'
}

key="horizon-$(date +%s)-$RANDOM"
first="$(post "$key" | jq -r .id)"
[ "$first" != "null" ] || fail "the first submit did not return an order"
echo "==> first submit          $first   duplicates=$(duplicates)"

# Push the recorded result out of the ten-entry Caffeine tier, so the filters
# and not the cache decide the next answer.
for index in $(seq 1 120); do post "evict-$key-$index" >/dev/null; done
inside="$(post "$key" | jq -r .id)"
echo "==> replay inside horizon $inside   duplicates=$(duplicates)"
[ "$inside" = "$first" ] || fail "the replay inside the horizon created a second order: $first then $inside"

echo "==> waiting out the horizon under load"
started="$(date +%s)"
while [ $(( $(date +%s) - started )) -lt 165 ]; do post "spin-$key-$(date +%s)-$RANDOM" >/dev/null; done
echo "    rotations so far: $(grep -c 'Deduplication index rotated' .local-run/logs/order-management-service.log)"

past="$(post "$key" | jq -r .id)"
after="$(duplicates)"
echo "==> replay past horizon   $past   duplicates=$after"
[ "$past" != "$first" ] || fail "the replay past the horizon was still deduplicated; the bound did not behave as documented"
# The second order carries the first order's commandId, so the writer's
# ON CONFLICT DO NOTHING absorbs it and reports it. This is the only place that
# branch runs outside AbsorbedConflictReportingSpec.
case "$after" in 0|0.0|"") fail "duplicate_reached_db did not move; the oracle did not see the duplicate";; esac

echo "==> the horizon behaves as documented, and the duplicate oracle fired"
echo "    run scripts/perf/reset-venue-state.sh --yes to return to the configured horizon"
