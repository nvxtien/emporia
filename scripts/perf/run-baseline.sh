#!/bin/bash
# REWORK_NOTE Phase 1_2 baseline run. Drives known load through the order path
# and records what latency actually does at each offered rate, so the knee
# and capacity numbers come from measurement instead of guesswork.
#
# Usage: scripts/perf/run-baseline.sh [probe|soak|trace|all]
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
#   TRACE_RATE=48          above-knee rate for the trace-correlation stage
#   TRACE_DURATION=90s     duration of the trace-correlation stage
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

STAGE="${1:-all}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8082}"
PROM_URL="${PROM_URL:-http://localhost:9090}"
ORIGIN="${EMPORIA_ORIGIN:-http://localhost:3001}"
REQUIRE_PROMETHEUS="${REQUIRE_PROMETHEUS:-true}"
MIN_FREE_GIB="${MIN_FREE_GIB:-10}"
PROBE_RATES="${PROBE_RATES:-5,10,20,40}"
PROBE_STEP="${PROBE_STEP:-60s}"
SOAK_DURATION="${SOAK_DURATION:-7m}"
# Knee detection factors, relative to the healthiest step observed. Latency is
# the primary signal because it inflects first and is what users feel.
KNEE_P50_FACTOR="${KNEE_P50_FACTOR:-2}"
KNEE_P99_FACTOR="${KNEE_P99_FACTOR:-3}"
# Warm-up before measuring. Without it the first probe steps absorb JIT
# compilation and class loading, so the earliest rates look unfairly slow and
# the curve is distorted at exactly the rates being characterised. Results are
# discarded.
WARMUP_RATE="${WARMUP_RATE:-3}"
WARMUP_DURATION="${WARMUP_DURATION:-60s}"

out_dir="${BASELINE_OUT_DIR:-$repo_root/.local-run/baseline/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$out_dir"

fail() { echo "FAIL: $*" >&2; exit 1; }

# --- guards ---------------------------------------------------------------

# The disk on this machine has repeatedly been the thing that breaks first, and
# a load run writes to six Postgres volumes, Tempo blocks, the Prometheus TSDB
# and exchange-core snapshots at once. Checked before every stage rather than
# only at startup, because the whole point is that the run itself consumes
# space.
#
# Two paths matter: the repo (exchange-core snapshots, logs, k6 output) and
# Docker's data store (Postgres, Tempo, Prometheus — where the bulk of the
# growth lands). They may or may not be the same volume, so resolve the
# Docker store rather than assuming it sits under $HOME: OrbStack can be
# relocated, and on this machine it is — data_dir is /Volumes/Work/orbstack,
# the *same* volume as the repo. Assuming $HOME reported 10 GiB free from the
# system volume while the volume Docker was actually filling had 5 GiB.
docker_data_dir() {
    local dir
    dir="$(sed -n 's/.*"data_dir"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
            "$HOME/.orbstack/vmconfig.json" 2>/dev/null | head -1)"
    [ -n "$dir" ] && [ -d "$dir" ] && { echo "$dir"; return; }
    # Docker Desktop keeps its disk image here; $HOME is the last resort.
    dir="$HOME/Library/Containers/com.docker.docker/Data"
    [ -d "$dir" ] && { echo "$dir"; return; }
    echo "$HOME"
}

free_gib() {
    df -k "$1" | awk 'NR==2 {printf "%d", $4 / 1024 / 1024}'
}

check_disk() {
    local repo_free docker_free
    repo_free="$(free_gib "$repo_root")"
    docker_free="$(free_gib "$(docker_data_dir)")"
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
    if [ "$REQUIRE_PROMETHEUS" != "false" ]; then
        curl -fsS "${PROM_URL}/-/healthy" >/dev/null 2>&1 || fail "Prometheus is not reachable at ${PROM_URL}"
    fi
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

# --- helpers ---------------------------------------------------------------

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

# Runs load whose results are thrown away, purely to get the JVMs compiled.
warm_up() {
    local token="$1"
    [ "$WARMUP_DURATION" = "0" ] && { echo "  -- warm-up skipped"; return 0; }
    echo "  -- warming up at ${WARMUP_RATE}/s for ${WARMUP_DURATION} (results discarded)"
    run_k6 "warmup" "$WARMUP_RATE" "$WARMUP_DURATION" "$token" >/dev/null 2>&1 || true
}

stage_probe() {
    echo "==> Stage A: capacity probe (${PROBE_RATES} orders/sec, ${PROBE_STEP} each)"
    check_disk
    local resting_at_start
    resting_at_start="$(docker exec "${PG_CONTAINER:-emporia-order-management-postgres}" psql -U postgres \
        -d emporia_order_management -tAc \
        "select count(*) from emporia_order_data.trading_order where order_status in ('LIVE','PARTIALLY_FILLED');" \
        2>/dev/null | tr -d ' ' || echo unknown)"
    echo "    resting orders at start: ${resting_at_start:-unknown}"
    local knee="" last_healthy="" first_degraded=""
    local best_p50="" best_p99=""
    local token; token="$(mint_token)"
    warm_up "$token"

    IFS=',' read -ra rates <<<"$PROBE_RATES"
    for rate in "${rates[@]}"; do
        echo "  -- offered ${rate}/s"
        run_k6 "probe-${rate}" "$rate" "$PROBE_STEP" "$token" || {
            echo "     k6 aborted at ${rate}/s (threshold breach)"
            knee="${knee:-$rate}"
            break
        }

        # Knee detection reads the latency curve, relative to the best step
        # seen: a rate is degraded once its p50 or p99 blows past the
        # healthiest measurement by these factors. Latency inflects first and
        # is what users feel - through 12/s in earlier baseline runs the
        # system reported zero failures while badly degraded, so error rate
        # alone would miss it just as badly.
        local p50 p99
        p50="$(k6_metric "probe-${rate}" submit_latency_p50)"
        p99="$(k6_metric "probe-${rate}" submit_latency_p99)"
        best_p50="$(awk -v a="$best_p50" -v b="$p50" 'BEGIN { print (a == "" || (b > 0 && b < a)) ? b : a }')"
        best_p99="$(awk -v a="$best_p99" -v b="$p99" 'BEGIN { print (a == "" || (b > 0 && b < a)) ? b : a }')"

        printf '     p50 %.0f ms, p99 %.0f ms\n' "$p50" "$p99"

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
        echo "warmup_rate=${WARMUP_RATE}"
        echo "warmup_duration=${WARMUP_DURATION}"
        # Venue latency decays as the resting book grows, so a knee is only
        # comparable against another run with a similar starting book size.
        echo "resting_orders_at_start=${resting_at_start:-unknown}"
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
    local token; token="$(mint_token)"
    run_k6 "soak" "$rate" "$SOAK_DURATION" "$token" || echo "     soak aborted (threshold breach)"
    echo "$rate" >"${out_dir}/soak-rate.txt"
    check_disk
}

# Stage C: prove a load spike can be correlated with traces, which is Phase
# 1_2's last outstanding success criterion.
#
# Running past the knee produces genuine sustained latency degradation to
# correlate against.
stage_trace() {
    local rate="${TRACE_RATE:-48}"
    local duration="${TRACE_DURATION:-90s}"

    echo "==> Stage C: trace correlation at ${rate}/s for ${duration} (above the knee)"
    check_disk

    local token; token="$(mint_token)"
    local start; start=$(date +%s)
    run_k6 "trace" "$rate" "$duration" "$token" || echo "     k6 aborted (threshold breach)"
    local end; end=$(date +%s)

    echo "  -- correlating"
    # Slow submits during the spike window. If the spike and traces are
    # correlated, this window should contain traces far slower than the
    # ~30ms seen below the knee.
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
        echo "slow_traces_over_1s=${slow_count}"
        echo "window_start=${start}"
        echo "window_end=$((end + 60))"
    } >"${out_dir}/trace-correlation.txt"

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

# --- main -----------------------------------------------------------------

echo "==> Emporia latency baseline"
echo "    output: ${out_dir}"
require_stack
check_disk
echo

case "$STAGE" in
    probe) stage_probe ;;
    soak)  stage_soak ;;
    trace) stage_trace ;;
    all)   stage_probe; echo; stage_soak; echo; stage_trace ;;
    *)     fail "unknown stage '${STAGE}' (expected probe, soak, trace, or all)" ;;
esac

echo
echo "==> Baseline artifacts in ${out_dir}"
ls -1 "$out_dir" | sed 's/^/    /'
