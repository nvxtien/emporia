# Exchange-Core Production Runbook

This runbook is for running Emporia with internal matching enabled by
`EXECUTION_VENUE_MODE=exchange-core`. It assumes the service is running on JDK 21
with PostgreSQL and a persistent exchange-core storage volume. Execution
routing (this runbook's subject) runs in-process inside
`order-management-service`, not as a separate deployable - see
[Order command flow](../../README.md#order-command-flow) in the repository
root README.

## Operating Principles

- Order management remains the order-state authority.
- Exchange-core owns the live matching engine, native book, and DMA lifecycle.
- `EXCHANGE_CORE_STORAGE_DIRECTORY` is production data, not cache.
- Do not delete, truncate, or replace exchange-core storage while
  `order-management-service` is running.
- Graceful shutdown should write a final checkpoint. Hard-kill recovery must be
  proven in staging before enabling journaling.

## Required Configuration

Set these before a production exchange-core deployment:

```bash
SPRING_PROFILES_ACTIVE=prod
EXECUTION_VENUE_MODE=exchange-core
EXCHANGE_CORE_STORAGE_DIRECTORY=/var/lib/emporia/exchange-core
EXCHANGE_CORE_MIN_FREE_STORAGE_BYTES=10737418240
EXCHANGE_CORE_RETAINED_CHECKPOINTS=2
EXCHANGE_CORE_JOURNALING=false
```

Use `EXCHANGE_CORE_ACCOUNTING_MODE=full-equity-risk` only when
`EXCHANGE_CORE_PORTFOLIO_URL` points at a healthy `portfolio-service` and client
balances are seeded. Keep `EXCHANGE_CORE_JOURNALING=false` until
`scripts/perf/crash-recovery-check.sh` passes in a production-like staging
environment.

`EXCHANGE_CORE_JOURNALING=true` moves full engine snapshots off the per-order
command path and is the primary execution catch-up-capacity lever. It remains a
gated production choice: enable it only after a hard-kill staging recovery pass,
then monitor checkpoint age and end-to-end order-submission latency during
rollout (see [Execution Catch-up Capacity](#execution-catch-up-capacity)
below).

Production guardrails intentionally fail startup when a production profile uses
`.local-run` storage or leaves `EXCHANGE_CORE_MIN_FREE_STORAGE_BYTES=0`.

## Startup Checklist

1. Verify the exchange-core storage path is a mounted persistent volume:

```bash
test -d "$EXCHANGE_CORE_STORAGE_DIRECTORY"
df -h "$EXCHANGE_CORE_STORAGE_DIRECTORY"
```

2. If using full-equity risk, confirm `portfolio-service` is healthy and seed any
   required exchange-core client balances before accepting orders.

3. Start `order-management-service` with the same storage path used by the
   prior successful instance.

4. Wait for readiness:

```bash
curl -fsS http://order-management-service:8086/actuator/health/readiness
curl -fsS http://order-management-service:8086/actuator/health
```

5. Confirm the startup log includes lifecycle rebuild output:

```bash
grep "Rebuilt venue lifecycle from order-management" order-management-service.log
```

## Checkpoint Validation

Validate the manifest and retained checkpoint files after startup and after every
deploy:

```bash
cat "$EXCHANGE_CORE_STORAGE_DIRECTORY/emporia-exchange-core.latest"
find "$EXCHANGE_CORE_STORAGE_DIRECTORY" -maxdepth 1 -type f | sort
```

The manifest checkpoint id must have exchange-core snapshot files and a DMA
lifecycle file. Older complete checkpoint generations may remain according to
`EXCHANGE_CORE_RETAINED_CHECKPOINTS`.

Micrometer meter names are dotted in code and rendered with underscores by
Prometheus. Inspect the checkpoint series:

```bash
curl -fsS http://order-management-service:8086/actuator/prometheus \
  | grep 'emporia_execution_venue_checkpoint'
```

Important signals:

| Signal | Expected production state |
|---|---|
| `checkpointStatusAvailable` | `true` in health details |
| `checkpointFailuresSinceLastSuccess` | `0` |
| `checkpointAgeSeconds` | At or below a small multiple of the configured snapshot interval once scheduled snapshots begin |
| `checkpointUsableStorageBytes` | Above `EXCHANGE_CORE_MIN_FREE_STORAGE_BYTES` |
| `partialCheckpointFileCount` | `0` after normal operation |

## Alert Response

### Checkpoint Failure

Treat any increase in `emporia.execution.venue.checkpoint.failures` as urgent.
Orders may have reached the venue before persistence failed.

1. Stop sending new order flow to this execution instance.
2. Check `/actuator/health`; it should report `DOWN` until a checkpoint succeeds.
3. Inspect storage free space and file permissions.
4. Restore storage or permissions, then allow the next scheduled checkpoint to
   clear health.
5. If failures continue, restart the instance on the same storage path.

### Stale Checkpoint Age

If checkpoint age grows materially beyond the configured snapshot interval:

1. Check whether `order-management-service` is still running and scheduled tasks are
   firing.
2. Inspect logs for periodic snapshot failures.
3. Confirm storage is writable and has free space.
4. Restart on the same storage path only after preserving logs and the current
   storage directory listing.

### Low Storage

If usable storage approaches `EXCHANGE_CORE_MIN_FREE_STORAGE_BYTES`:

1. Prefer expanding the volume.
2. If cleanup is required, stop `order-management-service` first.
3. Preserve the manifest checkpoint and retained complete checkpoint
   generations.
4. Move questionable files to a quarantine directory instead of deleting them
   immediately.

### Partial Checkpoint Files

`partialCheckpointFileCount > 0` means a checkpoint write left recognizable
partial files. Stale partial files older than the latest manifest checkpoint can
be pruned automatically after a newer successful checkpoint. Future partials are
kept for inspection.

1. Check whether the count clears after the next successful checkpoint.
2. If it persists, preserve logs and storage listings.
3. Stop `order-management-service` before manual cleanup.

## Crash Recovery

For an unexpected process or node crash:

1. Do not clear the exchange-core storage directory.
2. Restart `order-management-service` with the same
   `EXCHANGE_CORE_STORAGE_DIRECTORY`.
3. Wait for `/actuator/health/readiness`.
4. Confirm the lifecycle was rebuilt from order management:

```bash
grep "Rebuilt venue lifecycle from order-management" order-management-service.log
```

5. Confirm active orders can still be cancelled or modified through the gateway.
6. Check checkpoint health details and Prometheus checkpoint metrics.

Before enabling `EXCHANGE_CORE_JOURNALING=true`, run the staging crash-recovery
acceptance script:

```bash
EXCHANGE_CORE_JOURNALING=true scripts/perf/crash-recovery-check.sh
```

The script must pass after a hard kill. A graceful restart is not enough because
graceful shutdown writes a final checkpoint and can hide journal replay gaps.

## Execution Catch-up Capacity

If end-to-end order latency (p50/p95/p99, measured by
`scripts/perf/order-path-capacity.sh`) grows during capacity runs while
gateway and order-management stay healthy, first confirm whether the venue is
snapshotting per order:

```bash
grep "journaling=" order-management-service.log
```

With `EXCHANGE_CORE_JOURNALING=false`, every DMA command waits for a full engine
checkpoint. That is intentionally conservative, but latency grows as the book
grows. After the crash-recovery gate above passes, benchmark the journalled
path:

```bash
EXCHANGE_CORE_JOURNALING=true \
MAVEN_TEST_SKIP_ARGS=-Dmaven.test.skip=true \
scripts/run-infra-docker.sh

EXCHANGE_CORE_JOURNALING=true \
scripts/perf/order-path-capacity.sh
```

Acceptance for a catch-up-capacity improvement is lower end-to-end order
latency at the same offered rates with checkpoint metrics still present,
checkpoint failures at `0`, and no partial checkpoint files left behind.

> **Known gap**: `order-path-capacity.sh` still contains Kafka-consumer-group
> lag wait/drain logic from before the OMS/execution merge removed Kafka from
> this path entirely. That specific check now queries a Prometheus metric
> (`kafka_consumergroup_lag`) that no longer exists in this topology - it
> needs separate attention (see the repository root README for current
> architecture) before this section can be followed literally.

## Rollback

For an application rollback:

1. Stop new order flow.
2. Gracefully stop `order-management-service` and wait for shutdown checkpoint logs.
3. Start the previous application version with the same exchange-core storage
   path and the same exchange-core dependency version.
4. Verify readiness, lifecycle rebuild, checkpoint status, and order cancel or
   modify behavior.

If a rollback also changes database schema or exchange-core storage
compatibility, restore the order-management database and exchange-core storage
from a paired backup. Do not mix an older order-management projection with a
newer incompatible matching-engine checkpoint.

## Manual Storage Cleanup

Manual cleanup is allowed only while `order-management-service` is stopped.

1. Capture current state:

```bash
cat "$EXCHANGE_CORE_STORAGE_DIRECTORY/emporia-exchange-core.latest"
find "$EXCHANGE_CORE_STORAGE_DIRECTORY" -maxdepth 1 -type f | sort
```

2. Preserve:

- `emporia-exchange-core.latest`
- Files matching the latest manifest checkpoint id
- Retained complete previous checkpoint generations
- Journal files unless a separate recovery decision says they are obsolete

3. Move stale partial or unrelated files to a quarantine directory on the same
   volume.

4. Restart `order-management-service` with the same storage path and verify readiness.

Never use `scripts/perf/reset-venue-state.sh` in production. It is a destructive
local benchmark reset that deletes engine state and reconciles order-management
orders for performance testing only.

## Post-Deploy Acceptance

Run these checks after every production deploy:

```bash
curl -fsS http://order-management-service:8086/actuator/health/readiness
curl -fsS http://order-management-service:8086/actuator/prometheus \
  | grep 'emporia_execution_venue_checkpoint'
```

Confirm:

- `checkpointFailuresSinceLastSuccess=0`
- `checkpointStatusAvailable=true`
- checkpoint usable storage is above the configured floor
- partial checkpoint file count is zero or understood
- gateway order submit, cancel, and recovery smoke checks pass
