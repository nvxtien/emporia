# 📜 Emporia Platform Scripts & Tooling Guidelines

A comprehensive guide and reference catalog for all operational, performance benchmarking, deployment, and tuning scripts in the Emporia HFT platform.

---

## 📂 Directory Layout

```text
scripts/
├── run-local.sh                  # Main entrypoint: Start local stack in host JVM
├── stop-services.sh              # Main entrypoint: Gracefully stop all local services & Docker containers
├── clean-docker.sh               # Cleanup Utility: Purge Docker containers, data volumes, networks, and storage
├── launch-trading-core.sh        # Bare-Metal HFT Launcher with CPU Pinning & NUMA binding
├── run-infra-docker.sh           # Docker Compose infrastructure container manager
├── local-deploy.sh               # Full Docker Compose local deployment helper
├── local-ci.sh                   # Local CI build & test runner
├── init-databases.sh             # PostgreSQL initial database schema builder
├── seed-portfolio-client.sh      # Seed client portfolio DB records
├── install-git-hooks.sh          # Developer Git pre-commit hook installer
├── oidc-smoke-test.mjs           # Node.js Oauth2/OIDC auth flow smoke test
├── lib/                          # Shared Bash helper libraries (`run-common.sh`)
├── tuned/                        # Linux bare-metal kernel performance profiles (`tuned.conf`)
└── perf/                         # High-Performance Load Testing & Benchmarking Suite
    ├── run-order-load-test.sh    # Convenient wrapper for order load testing
    ├── order-path-capacity.sh    # Gateway-to-execution capacity benchmark with checkpoint metrics
    ├── order-submit-smoke.sh     # High-concurrency HTTP REST order submission test
    ├── exchange-core-benchmark.sh# LMAX Disruptor single-writer matching engine benchmark
    ├── market-data-fanout-smoke.sh# Aeron IPC / UDP Multicast quote fan-out load test
    ├── crash-recovery-check.sh   # Journaling checkpoint crash recovery test
    ├── first-request-check.sh    # Cold-start first request latency check
    ├── trace-smoke.sh            # OpenTelemetry trace propagation validator
    └── jfr-*.sh                  # Java Flight Recorder (JFR) profiling scripts
```

---

## 📐 Script Design Guidelines & Best Practices

All shell scripts in this repository adhere to the following strict operational standards:

### 1. Shell Environment & Strict Mode
- Use `#!/usr/bin/env bash` for portable interpreter resolution.
- Enable strict mode at the top of every script:
  ```bash
  set -euo pipefail
  ```

### 2. Cross-Platform BSD (macOS) & GNU (Linux) Portability
- **Do not rely on GNU-only CLI flags** (e.g., `date +%s%N` fails on macOS BSD `date`).
- Use portable millisecond timestamping:
  ```bash
  current_millis() {
      python3 -c 'import time; print(int(time.time()*1000))' 2>/dev/null || date +%s000
  }
  ```
- Use OS auto-detection for platform-specific CLI tools (e.g., `taskset` and `numactl` on Linux vs native execution on macOS):
  ```bash
  IS_LINUX=false
  if [[ "$(uname -s)" == "Linux" ]]; then
      IS_LINUX=true
  fi
  ```

### 3. Parameterization & Environment Overrides
- Provide safe default values for all environment variables:
  ```bash
  TARGET_URL="${TARGET_URL:-http://localhost:8086/orders}"
  TOTAL_REQUESTS="${TOTAL_REQUESTS:-5000}"
  CONCURRENCY="${CONCURRENCY:-10}"
  ```

### 4. `--dry-run` & `--help` CLI Flags
- Every benchmark and deployment script must support `--dry-run` to verify argument parsing and environment prerequisites without executing side-effects:
  ```bash
  for arg in "$@"; do
      if [[ "$arg" == "--dry-run" ]]; then
          echo "[Dry-Run Mode] Prerequisites verified."
          exit 0
      fi
  done
  ```

### 5. Idempotent Execution & Clean Teardown
- Use process PID tracking under `.local-run/pids/` and graceful teardown functions (`stop_pid_tree`).

---

## 🛠️ Categorized Usage Catalog

### A. Stack Lifecycle & Control Commands

#### `scripts/run-local.sh`
- **Purpose**: Starts the full Emporia microservice stack locally (Postgres in Docker; Spring Boot & React services in host JVM).
- **Usage**:
  ```bash
  ./scripts/run-local.sh
  ```
- **Access Endpoints**:
  - Frontend: `http://localhost:3001` (admin / admin123)
  - Gateway API: `http://localhost:8082`
  - Order Management Service (command + query hot path): `http://localhost:8086`
  - Grafana (Tempo + Prometheus): `http://localhost:3300`

#### `scripts/stop-services.sh`
- **Purpose**: Safely terminates all running host JVM services, Node processes, and Docker containers.
- **Usage**:
  ```bash
  ./scripts/stop-services.sh
  ```

#### `scripts/run-infra-docker.sh`
- **Purpose**: Starts only infrastructure containers (PostgreSQL, Grafana, OpenTelemetry Collector) without launching Spring Boot services.
- **Usage**:
  ```bash
  ./scripts/run-infra-docker.sh
  ```

---

### B. High-Frequency Trading (HFT) Bare-Metal Launchers

#### `scripts/launch-trading-core.sh`
- **Purpose**: Low-latency HFT JVM launcher applying bare-metal performance optimizations.
- **Features Applied**:
  - CPU Pinning: Pinned to isolated CPU core 3 (`taskset -c 3`) on Linux.
  - NUMA Binding: Bound to local memory node 0 (`numactl --membind=0`) on Linux.
  - Low-Latency JVM Flags: `-XX:+UseZGC`, `-XX:+AlwaysPreTouch`, `-XX:MaxDirectMemorySize=1024m`, `-Djava.lang.Integer.IntegerCache.high=65536`.
- **Usage**:
  ```bash
  # Test JVM flag assembly
  ./scripts/launch-trading-core.sh --dry-run

  # Production execution
  ./scripts/launch-trading-core.sh
  ```

#### `scripts/tuned/trading-profile/tuned.conf`
- **Purpose**: Bare-metal Linux system kernel tuning profile.
- **Settings**: CPU governor `performance`, THP disabled (`transparent_hugepages=never`), kernel network busy polling (`net.core.busy_poll=50`), real-time scheduler quota unlocked (`kernel.sched_rt_runtime_us=-1`).

---

### C. Performance & Load Testing Suite (`scripts/perf/`)

#### `scripts/perf/run-order-load-test.sh`
- **Purpose**: Convenient single-command wrapper for submitting high-concurrency order load.
- **Default Targets**: `TARGET_URL=http://localhost:8086/orders`, `TOTAL_REQUESTS=5000`.
- **Usage**:
  ```bash
  ./scripts/perf/run-order-load-test.sh
  ```

#### `scripts/perf/order-path-capacity.sh`
- **Purpose**: Capacity benchmark for the gateway -> order-management path (execution routing runs in-process), including k6 latency and exchange-core checkpoint health metrics.
- **Usage**:
  ```bash
  ORDER_PATH_RATES="5 10 20 40 60" PROBE_STEP=60s ./scripts/perf/order-path-capacity.sh
  ```
- **Journalled catch-up run**:
  ```bash
  EXCHANGE_CORE_JOURNALING=true MAVEN_TEST_SKIP_ARGS=-Dmaven.test.skip=true ./scripts/run-infra-docker.sh
  EXCHANGE_CORE_JOURNALING=true ./scripts/perf/order-path-capacity.sh
  ```
- **Artifacts**: Writes `summary.csv`, `run-notes.txt`, k6 logs, execution health JSON, and checkpoint Prometheus snapshots under `.local-run/order-path-capacity/<timestamp>`.
- **Pass criteria**: exits non-zero if any k6 rate step crosses its failure/rejection thresholds.

#### `scripts/perf/reset-venue-state.sh`
- **Purpose**: Destructive local-only reset for clean exchange-core capacity baselines.
- **Behavior**: Stops `order-management-service`, deletes local exchange-core storage, and force-cancels working orders in order-management.
- **Usage**:
  ```bash
  ./scripts/perf/reset-venue-state.sh --yes
  ```
- **Warning**: Do not use this in production; it deliberately discards local venue state.

#### `scripts/perf/order-submit-smoke.sh`
- **Purpose**: High-concurrency HTTP/REST order submission test.
- **Metrics Calculated**: Sustained TPS, p50/p99 latency, successful responses (200/201), overload rejections (HTTP 429), and failures.
- **Usage**:
  ```bash
  TOTAL_REQUESTS=10000 CONCURRENCY=20 ./scripts/perf/order-submit-smoke.sh
  ```

#### `scripts/perf/exchange-core-benchmark.sh`
- **Purpose**: In-process LMAX Disruptor single-writer matching engine micro-benchmark (sub-microsecond latency testing).
- **Usage**:
  ```bash
  ./scripts/perf/exchange-core-benchmark.sh
  ```

#### `scripts/perf/market-data-fanout-smoke.sh`
- **Purpose**: Aeron IPC / UDP Multicast quote fan-out throughput test (~1 µs target latency).
- **Usage**:
  ```bash
  ./scripts/perf/market-data-fanout-smoke.sh
  ```

---

## ⚡ Quick Reference Execution Summary

```bash
# 1. Start full local platform stack
./scripts/run-local.sh

# 2. Run order submission load test (5,000 requests)
./scripts/perf/run-order-load-test.sh

# 3. Run exchange core in-process matching benchmark
./scripts/perf/exchange-core-benchmark.sh

# 4. Stop all services and containers
./scripts/stop-services.sh
```
