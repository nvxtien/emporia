#!/bin/bash
# Renders text summaries from saved .jfr recordings (REWORK_NOTE Phase 1_4).
#
# Usage: scripts/perf/jfr-summary.sh .local-run/jfr/<timestamp>
#
# Produces one directory of text views per recording, so findings can be read,
# diffed and quoted without opening JDK Mission Control.
set -uo pipefail

# shellcheck source=lib/jfr-common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/jfr-common.sh"

run_dir="${1:-}"
if [ -z "$run_dir" ] && [ -f "$jfr_repo_root/.local-run/jfr/.last-run" ]; then
    run_dir="$(cat "$jfr_repo_root/.local-run/jfr/.last-run")"
fi
[ -n "$run_dir" ] || jfr_fail "usage: $0 <jfr-run-directory>"
[ -d "$run_dir" ] || jfr_fail "no such directory: $run_dir"

jdk_bin="$(jfr_jdk_bin)" || jfr_fail "the 'jfr' tool was not found.
  On macOS only some JDK tools are shimmed onto PATH - jcmd is, jfr is not - so
  this resolves it from /usr/libexec/java_home instead. Install a full JDK if
  the lookup fails."
JFR_TOOL="$jdk_bin/jfr"
echo "==> Using $JFR_TOOL ($("$JFR_TOOL" --version 2>&1 | head -1))"

# Verified against JDK 25; the plan's original names file-io / socket-io /
# monitor-blocked do not exist. Unknown views are skipped with a warning rather
# than failing the run, since view names differ between JDK versions.
VIEWS=(
    "hot-methods"
    "cpu-time-hot-methods"
    "allocation-by-class"
    "allocation-by-thread"
    "gc-pauses"
    "thread-allocation"
    "thread-count"
    "contention-by-class"
    "contention-by-thread"
    "pinned-threads"
    "file-reads-by-path"
    "file-writes-by-path"
    "socket-reads-by-host"
    "socket-writes-by-host"
    "exception-by-type"
    "latencies-by-type"
)

shopt -s nullglob
recordings=("$run_dir"/*.jfr)
[ ${#recordings[@]} -gt 0 ] || jfr_fail "no .jfr files in $run_dir"

for recording in "${recordings[@]}"; do
    service="$(basename "$recording" .jfr)"
    out="$run_dir/$service"
    mkdir -p "$out"
    echo "  -- $service"

    if ! "$JFR_TOOL" summary "$recording" > "$out/summary.txt" 2>/dev/null; then
        jfr_warn "could not summarise $service (recording may be empty or truncated)"
        continue
    fi

    rendered=0
    for view in "${VIEWS[@]}"; do
        if "$JFR_TOOL" view "$view" "$recording" > "$out/${view}.txt" 2>/dev/null; then
            # An empty view means the event simply never fired; keep the file so
            # its absence is distinguishable from the view being unsupported.
            rendered=$((rendered + 1))
        else
            rm -f "$out/${view}.txt"
            jfr_warn "view '$view' unavailable in this JDK; skipped"
        fi
    done
    echo "     ${rendered} views -> $out/"
done

echo "==> Summaries written under $run_dir"
[ -f "$run_dir/metadata.txt" ] && echo "    run context: $run_dir/metadata.txt"
exit 0
