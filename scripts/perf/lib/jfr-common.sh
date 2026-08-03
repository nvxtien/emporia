#!/bin/bash
# Shared helpers for the JFR profiling scripts (REWORK_NOTE Phase 1_4).
#
# Sourced by jfr-start.sh, jfr-stop.sh, jfr-summary.sh and jfr-run.sh. Kept in
# one place because the PID resolution and disk guard below are the two things
# that silently produce wrong or damaging results if each script reinvents them.

# Repo root, from scripts/perf/lib/ -> ../../..
jfr_repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

JFR_NAME="${JFR_NAME:-emporia-local}"
JFR_DURATION="${JFR_DURATION:-120s}"
JFR_SETTINGS="${JFR_SETTINGS:-profile}"
MIN_FREE_GIB="${MIN_FREE_GIB:-10}"

jfr_fail() { echo "FAIL: $*" >&2; exit 1; }
jfr_warn() { echo "  WARNING: $*" >&2; }

# --- tooling ---------------------------------------------------------------

# Resolves the JDK bin directory rather than trusting PATH.
#
# macOS ships shims for some JDK tools but not others: `jcmd` resolves to
# /usr/bin/jcmd while `jfr` is not on PATH at all. A script that assumes both
# are available appears to work, writes no summaries, and reports success.
jfr_jdk_bin() {
    local home
    home="$(/usr/libexec/java_home 2>/dev/null)" || home=""
    if [ -n "$home" ] && [ -x "$home/bin/jfr" ]; then
        echo "$home/bin"
        return 0
    fi
    # Fall back to PATH for non-macOS or unusual installs.
    local onpath
    onpath="$(command -v jfr 2>/dev/null)" && { dirname "$onpath"; return 0; }
    return 1
}

jfr_require_jcmd() {
    command -v jcmd >/dev/null 2>&1 || jfr_fail "jcmd is not available; a JDK (not just a JRE) is required"
}

# --- pid resolution --------------------------------------------------------

# Resolves a service name to the pid of the JVM actually running the app.
#
# start_service records the pid of `mvn spring-boot:run`, which forks the
# application into a child JVM. Attaching to the launcher yields a recording of
# Maven doing nothing - plausible-looking output that answers no question. The
# same mistake produced a meaningless "drain rate" in run-baseline.sh before it
# was caught, which is why this is centralised.
jfr_service_pid() {
    local service="$1"
    local pid_file="$jfr_repo_root/.local-run/pids/${service}.pid"
    [ -f "$pid_file" ] || { jfr_warn "no pid file for ${service} (host-JVM run mode required)"; return 1; }

    local launcher child
    launcher="$(cat "$pid_file")"
    kill -0 "$launcher" 2>/dev/null || { jfr_warn "${service} (pid ${launcher}) is not running"; return 1; }

    child="$(pgrep -P "$launcher" 2>/dev/null | head -1)"
    if [ -n "$child" ]; then
        echo "$child"
    else
        # Started directly as java rather than through Maven.
        echo "$launcher"
    fi
}

# --- disk ------------------------------------------------------------------

# Guards the volume the recordings are actually written to.
#
# Checks the output directory's own filesystem rather than a fixed path,
# because JFR_OUT_DIR is deliberately overridable: the repo volume on this
# machine has repeatedly been the first thing to fill, and pointing recordings
# at a roomier volume is a legitimate answer.
jfr_check_disk() {
    local target="$1"
    local probe="$target"
    # df needs a path that exists; walk up until one does.
    while [ ! -d "$probe" ] && [ "$probe" != "/" ]; do probe="$(dirname "$probe")"; done

    local free
    free="$(df -m "$probe" | awk 'NR==2 {printf "%d", $4}')"
    local free_gib=$(( free / 1024 ))
    echo "    free disk at ${probe}: ${free_gib} GiB"
    if [ "$free_gib" -lt "$MIN_FREE_GIB" ]; then
        jfr_fail "only ${free_gib} GiB free where recordings would be written, need ${MIN_FREE_GIB} GiB.
  JFR at settings=${JFR_SETTINGS} writes hundreds of MB per service. Either free space,
  point JFR_OUT_DIR at a roomier volume, or lower MIN_FREE_GIB deliberately."
    fi
}

# --- run context -----------------------------------------------------------

# Records what produced a recording, so a .jfr file found later can be tied
# back to a dashboard window and a code state.
jfr_write_metadata() {
    local out_dir="$1"; shift
    local services="$*"
    local now_epoch; now_epoch="$(date +%s)"
    {
        echo "captured_at        = $(date -Iseconds)"
        echo "epoch_seconds      = ${now_epoch}"
        echo "git_commit         = $(git -C "$jfr_repo_root" rev-parse --short HEAD 2>/dev/null || echo unknown)"
        echo "git_dirty          = $(test -n "$(git -C "$jfr_repo_root" status --porcelain 2>/dev/null)" && echo yes || echo no)"
        echo "services           = ${services}"
        echo "jfr_settings       = ${JFR_SETTINGS}"
        echo "jfr_duration       = ${JFR_DURATION}"
        echo "venue_mode         = ${EXECUTION_VENUE_MODE:-<default>}"
        echo "accounting_mode    = ${EXCHANGE_CORE_ACCOUNTING_MODE:-<default>}"
        echo "market_data        = ${MARKET_DATA_PROVIDER:-<default>}"
        echo "load_scenario      = ${JFR_SCENARIO:-<not recorded>}"
        echo
        echo "# Prometheus/Grafana window for correlation:"
        echo "grafana_from       = ${now_epoch}000"
        echo "grafana_to         = <fill in after the run>"
        echo "grafana_latency    = http://localhost:3300/d/emporia-latency-percentiles"
        echo "grafana_kafka_lag  = http://localhost:3300/d/emporia-kafka-lag"
    } > "$out_dir/metadata.txt"
}
