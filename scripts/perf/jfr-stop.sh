#!/bin/bash
# Dumps and stops active JFR recordings (REWORK_NOTE Phase 1_4).
#
# Usage: scripts/perf/jfr-stop.sh order-management-service
#
# Knobs: JFR_NAME, JFR_OUT_DIR (defaults to the directory jfr-start.sh last used)
#
# A missing recording is a warning rather than a failure: a partially collected
# run is still worth keeping, and this script also runs from jfr-run.sh's exit
# trap where the load command may have failed before every recording started.
set -uo pipefail

# shellcheck source=lib/jfr-common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/jfr-common.sh"

[ $# -gt 0 ] || jfr_fail "name at least one service, e.g. $0 order-management-service"

jfr_require_jcmd
last_run_marker="$jfr_repo_root/.local-run/jfr/.last-run"
if [ -z "${JFR_OUT_DIR:-}" ] && [ -f "$last_run_marker" ]; then
    JFR_OUT_DIR="$(cat "$last_run_marker")"
fi
JFR_OUT_DIR="${JFR_OUT_DIR:-$jfr_repo_root/.local-run/jfr/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$JFR_OUT_DIR"

echo "==> Stopping JFR recordings named '${JFR_NAME}'"
stopped=0
for service in "$@"; do
    pid="$(jfr_service_pid "$service")" || continue
    # Dump before stop: stopping first discards anything not yet written.
    if jcmd "$pid" JFR.dump name="$JFR_NAME" filename="$JFR_OUT_DIR/${service}.jfr" >/dev/null 2>&1; then
        jcmd "$pid" JFR.stop name="$JFR_NAME" >/dev/null 2>&1 || true
        size="$(du -m "$JFR_OUT_DIR/${service}.jfr" 2>/dev/null | awk '{print $1}')"
        echo "    ${service} (jvm ${pid}) -> ${service}.jfr (${size:-?} MB)"
        stopped=$((stopped + 1))
    else
        jfr_warn "no active recording named '${JFR_NAME}' on ${service}; it may have already hit its duration"
    fi
done

echo "==> ${stopped} recording(s) dumped to ${JFR_OUT_DIR}"
exit 0
