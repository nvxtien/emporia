#!/bin/bash
# Starts bounded JFR recordings on host-JVM services (REWORK_NOTE Phase 1_4).
#
# Usage: scripts/perf/jfr-start.sh order-management-service execution-service
#
# Knobs:
#   JFR_NAME=emporia-local     recording name, used again by jfr-stop.sh
#   JFR_DURATION=120s          recordings are always bounded
#   JFR_SETTINGS=profile       'profile' or 'default'
#   JFR_OUT_DIR=<auto>         defaults to .local-run/jfr/<timestamp>
#   JFR_SCENARIO=<text>        free-text note stored in metadata.txt
#   MIN_FREE_GIB=10            refuse to start below this on the output volume
#
# Profile only around a known hotspot: JFR is for explaining a spike that
# metrics or a trace already identified, not for running continuously.
set -euo pipefail

# shellcheck source=lib/jfr-common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/jfr-common.sh"

[ $# -gt 0 ] || jfr_fail "name at least one service, e.g. $0 order-management-service"

jfr_require_jcmd
JFR_OUT_DIR="${JFR_OUT_DIR:-$jfr_repo_root/.local-run/jfr/$(date +%Y%m%d-%H%M%S)}"

echo "==> Starting JFR (${JFR_SETTINGS}, ${JFR_DURATION})"
jfr_check_disk "$JFR_OUT_DIR"
mkdir -p "$JFR_OUT_DIR"

started=0
for service in "$@"; do
    pid="$(jfr_service_pid "$service")" || continue
    if jcmd "$pid" JFR.start \
            name="$JFR_NAME" \
            settings="$JFR_SETTINGS" \
            duration="$JFR_DURATION" \
            filename="$JFR_OUT_DIR/${service}.jfr" \
            dumponexit=true >/dev/null 2>&1; then
        echo "    ${service} (jvm ${pid}) -> ${service}.jfr"
        started=$((started + 1))
    else
        jfr_warn "could not start a recording on ${service} (jvm ${pid})"
    fi
done

[ "$started" -gt 0 ] || jfr_fail "no recordings started"

jfr_write_metadata "$JFR_OUT_DIR" "$@"
echo "==> ${started} recording(s) started; artifacts in ${JFR_OUT_DIR}"
echo "    Recordings stop themselves after ${JFR_DURATION}; use jfr-stop.sh to end them early."
echo "$JFR_OUT_DIR" > "$jfr_repo_root/.local-run/jfr/.last-run"
