#!/usr/bin/env bash
# ==============================================================================
# Exchange Core / Disruptor Pipeline Correctness Smoke Test
#
# Runs DisruptorOrderPipelineTest and TradingOrderPropertyTest. These are
# correctness tests, not a timing harness - this script does not measure
# latency or throughput, and must not print a latency/TPS number it never
# measured. For an actual latency measurement, profile a real run with
# scripts/perf/jfr-run.sh and read gc-pauses.txt / hot-methods.txt, or write
# a real JMH benchmark (there isn't one in this repo yet).
# ==============================================================================

set -euo pipefail

DRY_RUN=false

for arg in "$@"; do
    if [[ "$arg" == "--dry-run" ]]; then
        DRY_RUN=true
    fi
done

echo "==> Exchange Core / Disruptor Pipeline Correctness Smoke Test"
echo "    Engine: LMAX Disruptor 64K RingBuffer"
echo "    Arithmetic: Fixed-Point Primitive long (10^6 scale)"
echo "    This runs correctness tests only - no latency or throughput is measured."
echo

if [[ "$DRY_RUN" == "true" ]]; then
    echo "[Exchange Core Smoke Test] Dry-run mode. Test selection verified."
    exit 0
fi

echo "Running DisruptorOrderPipelineTest and TradingOrderPropertyTest via Maven..."
mvn -pl order-management-service test -Dtest=DisruptorOrderPipelineTest,TradingOrderPropertyTest -q

echo
echo "==> Correctness tests passed."
echo "    No latency/throughput number was measured by this script."
