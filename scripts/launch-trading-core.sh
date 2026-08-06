#!/usr/bin/env bash
# ==============================================================================
# Low-Latency HFT Trading Engine Launcher
# Supports macOS (Darwin) and Linux Bare-Metal OS Kernel Tuning & CPU Pinning
# ==============================================================================

set -euo pipefail

JAR_PATH="${JAR_PATH:-emporia/order-management-service/target/order-management-service-0.1.0-SNAPSHOT.jar}"
CPU_CORE="${CPU_CORE:-3}"
NUMA_NODE="${NUMA_NODE:-0}"
DRY_RUN=false

for arg in "$@"; do
    if [[ "$arg" == "--dry-run" ]]; then
        DRY_RUN=true
    fi
done

JVM_OPTS=(
    "-XX:+UseCompressedOops"
    "-XX:+AlwaysPreTouch"
    "-XX:-UseBiasedLocking"
    "-XX:+UseG1GC"
    "-XX:MaxGCPauseMillis=1"
    "-Djava.lang.Integer.IntegerCache.high=65536"
    "-Demporia.disruptor.cpu-set=${CPU_CORE}"
    "-Demporia.disruptor.numa-node=${NUMA_NODE}"
)

OS_NAME="$(uname -s)"
CMD=()

if [[ "$OS_NAME" == "Linux" ]]; then
    echo "[HFT Launcher] Detected Linux OS. Applying CPU Pinning (Core ${CPU_CORE}) & NUMA Binding (Node ${NUMA_NODE})..."
    if command -v numactl &>/dev/null; then
        CMD+=("numactl" "--membind=${NUMA_NODE}")
    fi
    if command -v taskset &>/dev/null; then
        CMD+=("taskset" "-c" "${CPU_CORE}")
    fi
elif [[ "$OS_NAME" == "Darwin" ]]; then
    echo "[HFT Launcher] Detected macOS (Darwin). Applying 100% low-latency JVM tuning flags (taskset/numactl bypassed)..."
else
    echo "[HFT Launcher] Detected OS: ${OS_NAME}. Applying JVM low-latency flags..."
fi

CMD+=("java" "${JVM_OPTS[@]}")

if [[ -f "$JAR_PATH" ]]; then
    CMD+=("-jar" "$JAR_PATH")
fi

echo "[HFT Launcher] Target Command Line:"
echo "  ${CMD[*]}"

if [[ "$DRY_RUN" == "true" ]]; then
    echo "[HFT Launcher] Dry-run complete. Exiting without launching JVM."
    exit 0
fi

exec "${CMD[@]}"
