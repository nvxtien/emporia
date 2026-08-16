#!/usr/bin/env bash
# Measures exchange-core's matching engine on its own, with JMH.
#
# Every other throughput number in this project is the HTTP path wrapped around
# the engine - gateway, authentication, the OMS single-writer ring, the WAL, the
# batched database write. None of them measures matching, so "is the matching
# engine efficient" has had no evidence either way. This is the only figure in
# the repository comparable to the ones exchanges publish.
#
# The journalling parameter is the point, not a knob: true runs the journal as a
# parallel Disruptor stage, false snapshots after every command. Running both
# separates "how fast does it match" from "how fast can it persist", which is
# the distinction the p99 investigation kept running into.
#
# Reads as an upper bound, not as Emporia's venue: matching-only accounting, no
# risk engine, no portfolio gateway. The configured venue runs full-equity-risk.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root/order-management-service"

WARMUP="${JMH_WARMUP:-3}"
ITERATIONS="${JMH_ITERATIONS:-5}"
RUNTIME="${JMH_RUNTIME:-5s}"
MODES="${JMH_MODES:-thrpt,sample}"
# Defaults to the round-trip benchmark only. MatchingEngineBenchmark.pipelinedSubmit
# HANGS: twice out of two runs the worker parked in join() on a future that never
# completed while the matching thread sat idle - at 1000 in flight and again at
# 256, an eighth of the 2048 ring, so ring capacity does not explain it. Run it
# deliberately with JMH_BENCHMARK=MatchingEngineBenchmark.pipelinedSubmit if you
# are investigating that, not by default.
BENCHMARK="${JMH_BENCHMARK:-MatchingEngineBenchmark.matchAlternatingSides}"

OUT="${JMH_OUT:-$repo_root/.local-run/matching-engine-benchmark/$(date +%Y%m%d-%H%M%S).json}"
mkdir -p "$(dirname "$OUT")"

echo "==> building the test classpath"
cp_file="$(mktemp)"
# -Dmatching: the benchmark drives exchange-core directly, so the agency
# default excludes it from compilation altogether.
mvn -o -q -Dmatching dependency:build-classpath -Dmdep.outputFile="$cp_file" -Dmdep.includeScope=test
mvn -o -q -Dmatching test-compile

echo "==> running: warmup=$WARMUP iterations=$ITERATIONS time=$RUNTIME modes=$MODES"
java -cp "target/classes:target/test-classes:$(cat "$cp_file")" \
     org.openjdk.jmh.Main "$BENCHMARK" \
     -wi "$WARMUP" -i "$ITERATIONS" -r "$RUNTIME" -f 1 -bm "$MODES" \
     -rf json -rff "$OUT"

echo "==> results written to $OUT"
