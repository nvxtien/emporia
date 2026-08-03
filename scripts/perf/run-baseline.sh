#!/bin/bash
# REWORK_NOTE Phase 1_2 baseline run. Drives known load through the order path
# and records what Kafka consumer lag actually does, so the warn/fail
# thresholds in kafka-lag-check.sh and the Grafana dashboard come from
# measurement instead of guesswork.
#
# Usage: scripts/perf/run-baseline.sh [probe|soak|stall|all]
#
# Requires a running stack (scripts/run-infra-docker.sh) plus the observability
# containers, and k6 on PATH.
#
# Knobs:
#   MIN_FREE_GIB=10        abort if free disk drops below this at any point
#   PROBE_RATES=5,10,20,40 offered rates for the capacity probe
#   PROBE_STEP=60s         hold time per probe step
#   SOAK_RATE=<auto>       steady-state rate; defaults to 60% of the knee
#   SOAK_DURATION=7m       must stay under the 10m access-token lifetime
#   STALL_SECONDS=30       how long to freeze the consumer in the stall stage
#   TRACE_RATE=48          above-knee rate for the trace-correlation stage
#   TRACE_DURATION=90s     duration of the trace-correlation stage
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

STAGE="${1:-all}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8082}"
PROM_URL="${PROM_URL:-http://localhost:9090}"
ORIGIN="${EMPORIA_ORIGIN:-http://localhost:3001}"
MIN_FREE_GIB="${MIN_FREE_GIB:-10}"
PROBE_RATES="${PROBE_RATES:-5,10,20,40}"
PROBE_STEP="${PROBE_STEP:-60s}"
SOAK_DURATION="${SOAK_DURATION:-7m}"
STALL_SECONDS="${STALL_SECONDS:-30}"
STALL_TARGET="${STALL_TARGET:-order-management-service}"
# Knee detection factors, relative to the healthiest step observed. Latency is
# the primary signal because lag and error rate both trail it badly.
KNEE_P50_FACTOR="${KNEE_P50_FACTOR:-2}"
KNEE_P99_FACTOR="${KNEE_P99_FACTOR:-3}"

STABLE_GROUPS='order-data-service-v1|emporia-execution-service-v1|order-management-executions-v1'

out_dir="${BASELINE_OUT_DIR:-$repo_root/.local-run/baseline/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$out_dir"

fail() { echo "FAIL: $*" >&2; exit 1; }

# --- guards ---------------------------------------------------------------

# The disk on this machine has repeatedly been the thing that breaks first, and
# a load run writes to six Postgres volumes, Kafka logs, Tempo blocks, the
# Prometheus TSDB and exchange-core snapshots at once. Checked before every
# stage rather than only at startup, because the whole point is that the run
# itself consumes space.
#
# Two separate volumes matter and they are not the same one. The repo (and so
# exchange-core snapshots, logs, k6 output) is on /Volumes/Work, while Docker's
# volumes — Postgres, Kafka, Tempo, Prometheus, which is where the bulk of the
# growth lands — are backed by the system volume that $HOME lives on. Checking
# only the repo path would let the container volumes fill the machine while
# this guard reported plenty of space.
free_gib() {
    df -k "$1" | awk 'NR==2 {printf "%d", $4 / 1024 / 1024}'
}

check_disk() {
    local repo_free docker_free
    repo_free="$(free_gib "$repo_root")"
    docker_free="$(free_gib "$HOME")"
    echo "    free disk: ${repo_free} GiB (repo), ${docker_free} GiB (docker volumes)"

    local volume
    for volume in "repo:${repo_free}" "docker:${docker_free}"; do
        local name="${volume%%:*}" free="${volume##*:}"
        if [ "$free" -lt "$MIN_FREE_GIB" ]; then
            fail "only ${free} GiB free on the ${name} volume, need ${MIN_FREE_GIB} GiB. Refusing to continue: filling this disk breaks Maven builds and the running stack. Free space or lower MIN_FREE_GIB deliberately."
        fi
    done
}

require_stack() {
    curl -fsS "${PROM_URL}/-/healthy" >/dev/null 2>&1 || fail "Prometheus is not reachable at ${PROM_URL}"
    local exporter_up
    exporter_up="$(promq 'up{job="kafka-exporter"}')"
    [ "$exporter_up" = "1" ] || fail "kafka-exporter is not up; every lag number would read as zero"
    command -v k6 >/dev/null 2>&1 || fail "k6 is not on PATH"
}

# Tokens live 10 minutes (authentication/.../application.yml) and emporia-web
# has no refresh_token grant, so a token cannot be renewed in place. Each stage
# therefore mints a fresh one and must stay comfortably under that lifetime.
mint_token() {
    EMPORIA_ORIGIN="$ORIGIN" \
    EMPORIA_USERNAME="${EMPORIA_USERNAME:-admin}" \
    EMPORIA_PASSWORD="${EMPORIA_PASSWORD:-admin123}" \
        node "$repo_root/scripts/perf/get-access-token.mjs" 2>/dev/null \
        || fail "could not mint an access token (is the stack up on ${ORIGIN}?)"
}

# --- prometheus helpers ---------------------------------------------------

promq() {
    curl -fsS --get "${PROM_URL}/api/v1/query" --data-urlencode "query=$1" 2>/dev/null \
        | python3 -c '
import json, sys
try:
    result = json.load(sys.stdin)["data"]["result"]
    print(result[0]["value"][1] if result else "0")
except Exception:
    print("0")
'
}

# Reads one field from a stage's k6 summary JSON.
k6_metric() {
    local name="$1" field="$2"
    python3 -c "
import json, sys
try:
    print(json.load(open('${out_dir}/${name}.k6.json')).get('${field}', 0))
except Exception:
    print(0)
" 2>/dev/null || echo 0
}

total_lag() { promq "sum(clamp_min(kafka_consumergroup_lag{consumergroup=~\"$STABLE_GROUPS\"}, 0))"; }

consume_rate() {
    promq "sum(kafka_consumer_fetch_manager_records_consumed_rate{topic=~\"emporia\\\\..+\"})"
}

# Captures the lag and consumption time series for a stage window so the
# numbers can be re-read later instead of only existing in the terminal.
snapshot_range() {
    local name="$1" start="$2" end="$3"
    for spec in \
        "lag_by_group|sum by (consumergroup) (clamp_min(kafka_consumergroup_lag{consumergroup=~\"$STABLE_GROUPS\"}, 0))" \
        "consumed_rate|sum by (service, topic) (kafka_consumer_fetch_manager_records_consumed_rate{topic=~\"emporia\\\\..+\"})" \
        "members|kafka_consumergroup_members{consumergroup=~\"$STABLE_GROUPS\"}"
    do
        local label="${spec%%|*}" query="${spec#*|}"
        curl -fsS --get "${PROM_URL}/api/v1/query_range" \
            --data-urlencode "query=${query}" \
            --data-urlencode "start=${start}" \
            --data-urlencode "end=${end}" \
            --data-urlencode "step=5" \
            -o "${out_dir}/${name}.${label}.json" 2>/dev/null || true
    done
}

wait_for_drain() {
    local deadline=$(( $(date +%s) + 300 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        local lag
        lag="$(total_lag)"
        # Integer compare via awk: lag is a float string from Prometheus.
        awk -v l="$lag" 'BEGIN { exit !(l < 1) }' && return 0
        sleep 2
    done
    echo "    WARNING: lag did not return to zero within 300s"
    return 1
}

run_k6() {
    local name="$1" rate="$2" duration="$3" token="$4"
    EMPORIA_TOKEN="$token" \
    GATEWAY_URL="$GATEWAY_URL" \
    SUMMARY_OUT="${out_dir}/${name}.k6.json" \
        k6 run --quiet \
            -e "RATE=${rate}" \
            -e "DURATION=${duration}" \
            -e "LISTING_IDS=${LISTING_IDS:-1}" \
            "$repo_root/scripts/perf/order-load.js" 2>&1 | tail -20
}

# --- stages ---------------------------------------------------------------

stage_probe() {
    echo "==> Stage A: capacity probe (${PROBE_RATES} orders/sec, ${PROBE_STEP} each)"
    check_disk
    local knee="" last_healthy="" first_degraded=""
    local best_p50="" best_p99=""
    local token; token="$(mint_token)"

    IFS=',' read -ra rates <<<"$PROBE_RATES"
    for rate in "${rates[@]}"; do
        echo "  -- offered ${rate}/s"
        wait_for_drain >/dev/null || true
        local start; start=$(date +%s)
        run_k6 "probe-${rate}" "$rate" "$PROBE_STEP" "$token" || {
            echo "     k6 aborted at ${rate}/s (threshold breach)"
            knee="${knee:-$rate}"
            break
        }
        local end; end=$(date +%s)
        snapshot_range "probe-${rate}" "$start" "$((end + 30))"

        local residual; residual="$(total_lag)"

        # Knee detection reads the latency curve, not the post-step lag.
        #
        # Lag was the original signal and it missed the knee in both baseline
        # runs: at 40/s in run 1 and at 16/s in run 2, where p50 was 2574 ms and
        # p99 sat on the reply timeout, the residual lag still drained and
        # nothing was flagged. Lag only appears once consumers fall behind,
        # which happens well after the submit path has degraded - and through
        # 12/s the system reported zero failures while badly degraded, so error
        # rate is no better.
        #
        # Latency inflects first and is what users feel, so the rule is relative
        # to the best step seen: a rate is degraded once its p50 or p99 blows
        # past the healthiest measurement by these factors.
        local p50 p99
        p50="$(k6_metric "probe-${rate}" submit_latency_p50)"
        p99="$(k6_metric "probe-${rate}" submit_latency_p99)"
        best_p50="$(awk -v a="$best_p50" -v b="$p50" 'BEGIN { print (a == "" || (b > 0 && b < a)) ? b : a }')"
        best_p99="$(awk -v a="$best_p99" -v b="$p99" 'BEGIN { print (a == "" || (b > 0 && b < a)) ? b : a }')"

        printf '     p50 %.0f ms, p99 %.0f ms, lag after step %s\n' "$p50" "$p99" "$residual"

        if awk -v p50="$p50" -v bp50="$best_p50" -v p99="$p99" -v bp99="$best_p99" \
               -v f50="$KNEE_P50_FACTOR" -v f99="$KNEE_P99_FACTOR" \
               'BEGIN { exit !(bp50 > 0 && bp99 > 0 && (p50 > bp50 * f50 || p99 > bp99 * f99)) }'; then
            echo "     degraded at ${rate}/s (p50 ${p50%.*}ms vs best ${best_p50%.*}ms, p99 ${p99%.*}ms vs best ${best_p99%.*}ms)"
            echo "     knee is the previous step: ${last_healthy:-none}/s"
            knee="${last_healthy}"
            first_degraded="$rate"
            break
        fi
        last_healthy="$rate"
        check_disk
    done

    {
        echo "knee_sustainable_rate=${knee:-none}"
        echo "first_degraded_rate=${first_degraded:-none}"
        echo "best_p50_ms=${best_p50:-0}"
        echo "best_p99_ms=${best_p99:-0}"
        echo "p50_factor=${KNEE_P50_FACTOR}"
        echo "p99_factor=${KNEE_P99_FACTOR}"
    } >"${out_dir}/knee.txt"
    if [ -n "$knee" ]; then
        echo "  knee: ${knee}/s sustainable; degradation begins at ${first_degraded}/s"
    else
        echo "  knee: not reached within ${PROBE_RATES} (all steps stayed within the latency factors)"
    fi
}

stage_soak() {
    local knee_file="${out_dir}/knee.txt"
    local knee="none"
    [ -f "$knee_file" ] && knee="$(cat "$knee_file")"

    local rate="${SOAK_RATE:-}"
    if [ -z "$rate" ]; then
        if [ "$knee" = "none" ] || [ -z "$knee" ]; then
            # No knee found means the probe never saturated the system, so the
            # highest probed rate is a safe steady state.
            rate="${PROBE_RATES##*,}"
        else
            rate=$(( knee * 60 / 100 ))
            [ "$rate" -lt 1 ] && rate=1
        fi
    fi

    echo "==> Stage B: steady-state soak at ${rate}/s for ${SOAK_DURATION}"
    check_disk
    wait_for_drain >/dev/null || true
    local token; token="$(mint_token)"
    local start; start=$(date +%s)
    run_k6 "soak" "$rate" "$SOAK_DURATION" "$token" || echo "     soak aborted (threshold breach)"
    local end; end=$(date +%s)
    snapshot_range "soak" "$start" "$((end + 30))"
    echo "$rate" >"${out_dir}/soak-rate.txt"
    check_disk
}

stage_stall() {
    local rate="${STALL_RATE:-$(cat "${out_dir}/soak-rate.txt" 2>/dev/null || echo 5)}"
    local pid_file="$repo_root/.local-run/pids/${STALL_TARGET}.pid"
    [ -f "$pid_file" ] || fail "no pid file for ${STALL_TARGET}; this stage needs the host-JVM run mode (scripts/run-infra-docker.sh)"
    local pid; pid="$(cat "$pid_file")"
    kill -0 "$pid" 2>/dev/null || fail "${STALL_TARGET} (pid ${pid}) is not running"

    # The pid file records the `mvn spring-boot:run` launcher, which forks the
    # application into a child JVM — the same reason stop_pid_tree kills
    # children first. Signalling the launcher freezes the wrapper while the
    # consumer keeps polling, so the stall silently measures nothing: lag stays
    # near zero and the derived drain rate is meaningless.
    local child
    child="$(pgrep -P "$pid" | head -1)"
    if [ -n "$child" ]; then
        echo "    pid ${pid} is the Maven launcher; stalling its application JVM ${child} instead"
        pid="$child"
    fi

    echo "==> Stage C: deliberate ${STALL_SECONDS}s consumer stall on ${STALL_TARGET} (pid ${pid}) at ${rate}/s"
    echo "    This is what makes the fail threshold principled: it measures how"
    echo "    fast a real backlog drains, so 'fail' can mean 'cannot recover'"
    echo "    rather than an arbitrary record count."
    check_disk
    wait_for_drain >/dev/null || true

    local token; token="$(mint_token)"
    local start; start=$(date +%s)

    # SIGSTOP rather than a stop/restart cycle: it freezes the consumer without
    # the restart and re-warm complexity, and a stall shorter than Kafka's 45s
    # session timeout keeps the group membership intact, so this measures pure
    # backlog accumulation rather than a rebalance.
    local duration_seconds=$(( STALL_SECONDS * 3 ))
    EMPORIA_TOKEN="$token" GATEWAY_URL="$GATEWAY_URL" \
    SUMMARY_OUT="${out_dir}/stall.k6.json" \
        k6 run --quiet -e "RATE=${rate}" -e "DURATION=${duration_seconds}s" \
            -e "LISTING_IDS=${LISTING_IDS:-1}" \
            "$repo_root/scripts/perf/order-load.js" >"${out_dir}/stall.k6.log" 2>&1 &
    local k6_pid=$!

    sleep 5
    echo "    freezing ${STALL_TARGET}"
    kill -STOP "$pid"
    local frozen_at; frozen_at=$(date +%s)
    sleep "$STALL_SECONDS"
    local peak_lag; peak_lag="$(total_lag)"
    echo "    lag at end of stall: ${peak_lag}"
    kill -CONT "$pid"
    echo "    resumed; measuring drain"

    local resumed_at; resumed_at=$(date +%s)
    wait_for_drain || true
    local drained_at; drained_at=$(date +%s)
    local recovery=$(( drained_at - resumed_at ))

    wait "$k6_pid" 2>/dev/null || true
    local end; end=$(date +%s)
    snapshot_range "stall" "$start" "$((end + 30))"

    local accumulation drain_rate
    accumulation=$(awk -v p="$peak_lag" -v s="$STALL_SECONDS" 'BEGIN { printf "%.1f", p / s }')
    drain_rate=$(awk -v p="$peak_lag" -v r="$recovery" 'BEGIN { printf "%.1f", (r > 0 ? p / r : 0) }')

    cat >"${out_dir}/stall-summary.txt" <<EOF
offered_rate=${rate}
stall_seconds=${STALL_SECONDS}
peak_lag=${peak_lag}
accumulation_per_second=${accumulation}
recovery_seconds=${recovery}
drain_rate_per_second=${drain_rate}
EOF
    echo "    peak lag ${peak_lag}, recovered in ${recovery}s, drain rate ${drain_rate}/s"
    check_disk
}

# Stage D: prove a lag spike can be correlated with traces, which is Phase 1_2's
# last outstanding success criterion.
#
# The spike is produced by running *above* capacity rather than by stalling a
# consumer. Stalling order-management-service does not build a backlog: order
# submission is synchronously coupled to it, so freezing it blocks the front
# door instead and the system fails closed (see PHASE_1_2_BASELINE.md finding 3).
# Running past the ~40/s knee produces genuine sustained lag.
stage_trace() {
    local rate="${TRACE_RATE:-48}"
    local duration="${TRACE_DURATION:-90s}"

    echo "==> Stage D: trace correlation at ${rate}/s for ${duration} (above the ~40/s knee)"
    check_disk
    wait_for_drain >/dev/null || true

    local token; token="$(mint_token)"
    local start; start=$(date +%s)
    run_k6 "trace" "$rate" "$duration" "$token" || echo "     k6 aborted (threshold breach)"
    local end; end=$(date +%s)

    local peak_lag; peak_lag="$(total_lag)"
    snapshot_range "trace" "$start" "$((end + 30))"

    echo "  -- correlating"
    # Slow submits during the spike window. If lag and traces are correlated,
    # this window should contain traces far slower than the ~30ms seen below
    # the knee.
    local slow_count slow_ids
    slow_ids="$(curl -fsS --get "${TEMPO_URL:-http://localhost:3200}/api/search" \
        --data-urlencode 'q={ name="emporia.order.submit" && duration > 1s }' \
        --data-urlencode "start=${start}" \
        --data-urlencode "end=$((end + 60))" \
        --data-urlencode 'limit=50' 2>/dev/null \
        | python3 -c '
import json, sys
try:
    traces = json.load(sys.stdin).get("traces") or []
except Exception:
    traces = []
for t in traces:
    print("%s %sms" % (t.get("traceID",""), t.get("durationMs","?")))
')"
    slow_count="$(printf '%s\n' "$slow_ids" | grep -c . || true)"

    {
        echo "offered_rate=${rate}"
        echo "peak_lag=${peak_lag}"
        echo "slow_traces_over_1s=${slow_count}"
        echo "window_start=${start}"
        echo "window_end=$((end + 60))"
    } >"${out_dir}/trace-correlation.txt"

    echo "    peak lag during window : ${peak_lag}"
    echo "    submit traces > 1s     : ${slow_count}"
    if [ "$slow_count" -gt 0 ]; then
        echo "    examples (traceID duration):"
        printf '%s\n' "$slow_ids" | head -5 | sed 's/^/      /'
        echo
        echo "    Inspect in Grafana:  http://localhost:3300/explore  (Tempo datasource)"
        echo "    Or directly:         ${TEMPO_URL:-http://localhost:3200}/api/traces/<traceID>"
    else
        echo "    WARNING: no slow submit traces found; correlation not demonstrated"
    fi
    check_disk
}

derive_thresholds() {
    local summary="${out_dir}/stall-summary.txt"
    [ -f "$summary" ] || { echo "  (no stall data; thresholds cannot be derived)"; return; }
    # shellcheck disable=SC1090
    . "$summary"

    # A stall that failed to actually stall anything still produces numbers, and
    # those numbers look entirely plausible once the floors below are applied.
    # Refuse to emit thresholds unless the stall built a backlog roughly
    # consistent with the offered rate, so a broken stage is loud rather than
    # quietly authoritative.
    local expected_min
    expected_min=$(awk -v r="$offered_rate" -v s="$stall_seconds" 'BEGIN { printf "%d", r * s * 0.1 }')
    if awk -v p="$peak_lag" -v m="$expected_min" 'BEGIN { exit !(p < m) }'; then
        echo
        echo "  REFUSING to derive thresholds: peak lag ${peak_lag} is far below the"
        echo "  ~${expected_min}+ expected from ${offered_rate}/s for ${stall_seconds}s."
        echo "  The consumer almost certainly kept running through the stall, so the"
        echo "  drain rate is meaningless. Check that the stalled pid is the"
        echo "  application JVM and not the Maven launcher."
        return 1
    fi

    # Thresholds are defined in seconds of backlog and then converted to record
    # counts, because a record count on its own says nothing: 500 records is a
    # 50ms blip at 10k/s and a 25s incident at 20/s. Recording the drain rate
    # means these can be re-derived rather than re-guessed on other hardware.
    local warn fail_threshold
    warn=$(awk -v d="$drain_rate_per_second" 'BEGIN { printf "%d", (d * 5 < 50 ? 50 : d * 5) }')
    fail_threshold=$(awk -v d="$drain_rate_per_second" 'BEGIN { printf "%d", (d * 30 < 200 ? 200 : d * 30) }')

    cat >"${out_dir}/derived-thresholds.txt" <<EOF
drain_rate_per_second=${drain_rate_per_second}
warn_threshold=${warn}      # ~5s of backlog
fail_threshold=${fail_threshold}   # ~30s of backlog
EOF
    echo
    echo "==> Derived thresholds (5s / 30s of backlog at measured drain rate)"
    echo "    drain rate      : ${drain_rate_per_second} records/sec"
    echo "    KAFKA_LAG_WARN_THRESHOLD=${warn}"
    echo "    KAFKA_LAG_FAIL_THRESHOLD=${fail_threshold}"
}

# --- main -----------------------------------------------------------------

echo "==> Emporia Kafka lag baseline"
echo "    output: ${out_dir}"
require_stack
check_disk
echo

case "$STAGE" in
    probe) stage_probe ;;
    soak)  stage_soak ;;
    stall) stage_stall; derive_thresholds ;;
    trace) stage_trace ;;
    all)   stage_probe; echo; stage_soak; echo; stage_stall; derive_thresholds; echo; stage_trace ;;
    *)     fail "unknown stage '${STAGE}' (expected probe, soak, stall, trace, or all)" ;;
esac

echo
echo "==> Baseline artifacts in ${out_dir}"
ls -1 "$out_dir" | sed 's/^/    /'
