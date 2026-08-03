#!/bin/bash
# REWORK_NOTE Phase 1_2: local smoke check for Kafka consumer group lag.
#
# Deliberately talks to the broker through `kafka-consumer-groups` rather than
# Prometheus. Broker-side offsets are the source of truth for lag, and a check
# that depends on the observability stack cannot tell you anything when that
# stack is the thing that is down. Grafana reads the exporter instead; the two
# paths are independent on purpose.
#
# Usage: scripts/perf/kafka-lag-check.sh
#
# Knobs:
#   KAFKA_BOOTSTRAP_SERVERS     broker address as seen from wherever the CLI
#                               runs (default kafka:9092 inside the container)
#   KAFKA_LAG_WARN_THRESHOLD    per-group total lag that prints a warning (50)
#   KAFKA_LAG_FAIL_THRESHOLD    per-group total lag that fails the check (500)
#   KAFKA_LAG_INCLUDE_EPHEMERAL show the random-uuid groups too (false)
#
# Exit codes: 0 healthy (warnings allowed), 1 unhealthy.
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-kafka:9092}"
# Measured, not guessed — see rework/PHASE_1_2_BASELINE.md. At or below the
# sustainable rate (~40 orders/sec on the baseline machine) lag is 0-1, while
# past-capacity operation sits at 123-199, so sustained lag above 50 already
# means the system is behind. 500 is above every value observed in any stage,
# including the 314 peak during a deliberate consumer stall.
WARN_THRESHOLD="${KAFKA_LAG_WARN_THRESHOLD:-50}"
FAIL_THRESHOLD="${KAFKA_LAG_FAIL_THRESHOLD:-500}"
INCLUDE_EPHEMERAL="${KAFKA_LAG_INCLUDE_EPHEMERAL:-false}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-emporia-kafka}"

# Stable groups that represent durable business processing. These are the only
# groups allowed to fail the check. Kept as a static list, per the plan, so the
# check can detect a group that has vanished entirely — discovering groups from
# the broker would silently pass in exactly that case.
STABLE_GROUPS=(
    "order-data-service-v1|emporia.order.commands.v1"
    "emporia-execution-service-v1|emporia.orders.v1"
    "order-management-executions-v1|emporia.execution.commands.v1"
)

# Ephemeral groups are named with a random uuid per process instance. They are
# expected to appear and disappear, so they are informational only and never
# fail the check.
EPHEMERAL_PREFIXES=(order-command-service- order-stream-)

exit_code=0
warnings=0

fail() { echo "FAIL: $*" >&2; exit 1; }

# Prefer the CLI inside the Kafka container so the script works with no local
# Kafka install, which is the normal state for this repo.
if docker exec "$KAFKA_CONTAINER" true >/dev/null 2>&1; then
    kafka_cg() {
        docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-consumer-groups.sh \
            --bootstrap-server "$BOOTSTRAP" "$@" 2>/dev/null
    }
elif command -v kafka-consumer-groups.sh >/dev/null 2>&1; then
    # Falling back to a host install means the in-container address is wrong.
    [ -n "${KAFKA_BOOTSTRAP_SERVERS:-}" ] || BOOTSTRAP="localhost:9092"
    kafka_cg() { kafka-consumer-groups.sh --bootstrap-server "$BOOTSTRAP" "$@" 2>/dev/null; }
else
    fail "no way to reach Kafka: container '${KAFKA_CONTAINER}' is not running and kafka-consumer-groups.sh is not on PATH"
fi

echo "==> Kafka consumer lag (bootstrap ${BOOTSTRAP})"
echo "    warn at ${WARN_THRESHOLD}, fail at ${FAIL_THRESHOLD} total lag per group"
echo

existing_groups="$(kafka_cg --list || true)"
[ -n "$existing_groups" ] || fail "could not list consumer groups (is the broker up?)"

# Parses one group's describe output into "<effective_lag> <detail>" lines plus
# a TOTAL line. A partition showing CURRENT-OFFSET '-' has no committed offset:
# effective lag is 0 when the partition is also empty, but the full end offset
# when the broker holds records, because nothing has ever been consumed. Reading
# the literal '-' in the LAG column as 0 would hide a completely stalled
# partition, which is the failure this check exists to catch.
summarise_group() {
    awk -v warn="$WARN_THRESHOLD" '
        NR == 1 || $1 == "GROUP" || NF < 6 { next }
        {
            group = $1; topic = $2; partition = $3
            current = $4; end_offset = $5; lag = $6

            if (current == "-") {
                effective = (end_offset ~ /^[0-9]+$/ && end_offset > 0) ? end_offset : 0
                note = (effective > 0) ? "  <- no committed offset, never consumed" : "  <- no committed offset, partition empty"
            } else if (lag ~ /^[0-9]+$/) {
                effective = lag
                note = (lag >= warn) ? "  <- above warn threshold" : ""
            } else {
                effective = 0
                note = "  <- unparsed lag \"" lag "\""
            }

            total += effective
            if (effective > max) max = effective
            printf "      %-30s p%-3s lag %-8s%s\n", topic, partition, effective, note
            partitions++
        }
        END { printf "TOTAL %d %d %d\n", total + 0, max + 0, partitions + 0 }
    '
}

for entry in "${STABLE_GROUPS[@]}"; do
    group="${entry%%|*}"
    expected_topic="${entry##*|}"

    if ! printf '%s\n' "$existing_groups" | grep -qx "$group"; then
        echo "  [MISSING] ${group}"
        echo "      expected to consume ${expected_topic}, but the group does not exist"
        echo "      the owning service is down, or has never connected since the topic was created"
        exit_code=1
        echo
        continue
    fi

    describe="$(kafka_cg --describe --group "$group" || true)"
    summary="$(printf '%s\n' "$describe" | summarise_group)"
    detail="$(printf '%s\n' "$summary" | grep -v '^TOTAL ')"
    read -r _ total max partitions <<<"$(printf '%s\n' "$summary" | grep '^TOTAL ')"

    # A group can exist with committed offsets but no live member: the service
    # died and left its offsets behind. Lag looks fine right up until the
    # backlog builds, so membership is checked separately from lag.
    members="$(printf '%s\n' "$describe" | awk 'NR > 1 && NF >= 7 && $7 != "-" && $7 != "CONSUMER-ID"' | wc -l | tr -d ' ')"

    if [ "$partitions" = "0" ]; then
        echo "  [MISSING] ${group}"
        echo "      the group exists but reports no partitions for ${expected_topic}"
        exit_code=1
    elif [ "$members" = "0" ]; then
        echo "  [NO CONSUMER] ${group} — total lag ${total}, max partition lag ${max}"
        echo "      offsets exist but no member is assigned; the owning service is not running"
        exit_code=1
    elif [ "$total" -ge "$FAIL_THRESHOLD" ]; then
        echo "  [FAIL] ${group} — total lag ${total}, max partition lag ${max}"
        exit_code=1
    elif [ "$total" -ge "$WARN_THRESHOLD" ]; then
        echo "  [WARN] ${group} — total lag ${total}, max partition lag ${max}"
        warnings=$((warnings + 1))
    else
        echo "  [OK] ${group} — total lag ${total}, max partition lag ${max}"
    fi

    printf '%s\n' "$detail"
    echo
done

if [ "$INCLUDE_EPHEMERAL" = "true" ]; then
    echo "==> Ephemeral groups (informational, never fail the check)"
    found_ephemeral=false
    while IFS= read -r group; do
        [ -n "$group" ] || continue
        for prefix in "${EPHEMERAL_PREFIXES[@]}"; do
            case "$group" in
                "$prefix"*)
                    found_ephemeral=true
                    summary="$(kafka_cg --describe --group "$group" | summarise_group || true)"
                    read -r _ total max _ <<<"$(printf '%s\n' "$summary" | grep '^TOTAL ' || echo "TOTAL 0 0 0")"
                    echo "  [INFO] ${group} — total lag ${total}, max partition lag ${max}"
                    printf '%s\n' "$summary" | grep -v '^TOTAL ' || true
                    ;;
            esac
        done
    done <<<"$existing_groups"
    [ "$found_ephemeral" = true ] || echo "  none present"
    echo
fi

if [ "$exit_code" -ne 0 ]; then
    echo "==> Kafka lag check FAILED"
elif [ "$warnings" -gt 0 ]; then
    echo "==> Kafka lag check passed with ${warnings} warning(s)"
else
    echo "==> Kafka lag check passed"
fi
exit "$exit_code"
