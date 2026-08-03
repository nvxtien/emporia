#!/bin/bash
# Wraps a load scenario in JFR start/stop/summary (REWORK_NOTE Phase 1_4).
#
# Usage:
#   scripts/perf/jfr-run.sh --services order-command-service,execution-service -- \
#       k6 run -e RATE=48 -e DURATION=90s scripts/perf/order-load.js
#
# This is the normal way to collect JFR during Phase 1_2/1_3 load work: it keeps
# the recording window aligned with the load, and dumps recordings even when the
# load command fails - a failed run is usually the one worth profiling.
#
# Knobs: JFR_NAME, JFR_DURATION, JFR_SETTINGS, JFR_OUT_DIR, MIN_FREE_GIB,
#        JFR_SCENARIO (free-text note recorded in metadata.txt)
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/jfr-common.sh
source "$script_dir/lib/jfr-common.sh"

SERVICES="order-command-service,order-management-service,execution-service"
while [ $# -gt 0 ]; do
    case "$1" in
        --services) SERVICES="${2:-}"; shift 2 ;;
        --) shift; break ;;
        *) jfr_fail "unexpected argument '$1' (did you forget -- before the load command?)" ;;
    esac
done
[ $# -gt 0 ] || jfr_fail "no load command given after --"

IFS=',' read -ra service_list <<<"$SERVICES"

export JFR_OUT_DIR="${JFR_OUT_DIR:-$jfr_repo_root/.local-run/jfr/$(date +%Y%m%d-%H%M%S)}"
export JFR_SCENARIO="${JFR_SCENARIO:-$*}"

started_ok=0
# Dump whatever exists however this exits, so a load failure still leaves
# evidence rather than losing the window it was meant to capture.
cleanup() {
    local status=$?
    if [ "$started_ok" -eq 1 ]; then
        echo
        "$script_dir/jfr-stop.sh" "${service_list[@]}" || true
        echo
        "$script_dir/jfr-summary.sh" "$JFR_OUT_DIR" || true
    fi
    echo
    echo "==> Artifacts: $JFR_OUT_DIR"
    exit "$status"
}
trap cleanup EXIT INT TERM

"$script_dir/jfr-start.sh" "${service_list[@]}"
started_ok=1

echo
echo "==> Running load: $*"
set +e
"$@"
load_status=$?
set -e
echo "==> Load command exited with ${load_status}"

# Record the end of the window so the metadata points at a real Grafana range.
if [ -f "$JFR_OUT_DIR/metadata.txt" ]; then
    sed -i '' "s|^grafana_to .*|grafana_to         = $(date +%s)000|" "$JFR_OUT_DIR/metadata.txt" 2>/dev/null \
        || sed -i "s|^grafana_to .*|grafana_to         = $(date +%s)000|" "$JFR_OUT_DIR/metadata.txt" 2>/dev/null || true
    echo "load_exit_status   = ${load_status}" >> "$JFR_OUT_DIR/metadata.txt"
fi

exit "$load_status"
