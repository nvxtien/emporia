#!/bin/bash
# Phase 9 rollout driver for OMS hot-path shadow/canary checks.
#
# The script does not mutate deployment state. It gives a repeatable operator
# sequence for:
# 1. shadow comparison
# 2. canary load by listing groups
# 3. kill-switch drill
#
# Usage:
#   SYMBOL_GROUPS='1,2;3,4' scripts/perf/hotpath-rollout.sh
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

SYMBOL_GROUPS="${SYMBOL_GROUPS:-1}"
RATE="${RATE:-20}"
DURATION="${DURATION:-45s}"
PROFILE="${PROFILE:-0}"

IFS=';' read -ra groups <<<"$SYMBOL_GROUPS"

echo "==> Stage 1: shadow-only baseline"
SHADOW_LIMIT="${SHADOW_LIMIT:-500}" PROFILE=0 "$repo_root/scripts/perf/hotpath-acceptance.sh"

stage=2
for group in "${groups[@]}"; do
    echo
    echo "==> Stage ${stage}: canary group listings=${group}"
    LISTING_IDS="$group" RATE="$RATE" DURATION="$DURATION" PROFILE="$PROFILE" \
        "$repo_root/scripts/perf/hotpath-acceptance.sh"
    stage=$((stage + 1))
done

echo
echo "==> Rollout checklist complete"
echo "    - shadow replay compared before canaries"
echo "    - canary groups executed sequentially"
echo "    - kill-switch drill executed in each acceptance run"
