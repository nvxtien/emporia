# Configuration Notes

Non-obvious configuration facts discovered through real incidents, kept here
so they don't have to be rediscovered. Add an entry when a config default
silently produces misleading results and the fix isn't obvious from reading
the code alone.

## exchange-core `wait-strategy` must be `yielding`, not the `busy-spin` default

- **Where**: `order-management-service/src/main/resources/application.yml`,
  key `emporia.execution.exchange-core.wait-strategy`
  (env override: `EXCHANGE_CORE_WAIT_STRATEGY`)
- **Default in code**: `busy-spin`
- **Required value for the current merged process**: `yielding`

**Why**: `busy-spin` was safe when `execution-service` ran as its own
process. Since the 2026-08 rework merged execution routing in-process into
`order-management-service`, `busy-spin` makes exchange-core's internal
matching-engine threads spin at 100% CPU continuously, competing for the
same cores as the OMS Disruptor writer thread and
`ShardedOrderDispatcher`'s shard worker threads.

**Measured impact** (JFR-verified 2026-08-13; identical 40 orders/sec, 90s
load, only this one env var changed between runs):

| | `busy-spin` (default) | `yielding` |
|---|---:|---:|
| p50 | 104 ms | 7 ms |
| p99 | 1,018 ms | 31 ms |
| Infra failure rate | 5.4% | 0% |
| CPU samples in busy-spin loops | 88.5% | ~2% |

Three other explanations (JVM cold-start, one-sided vs. mixed order flow,
`PROBE_STEP`/checkpoint-interval resonance) were tested and disproven before
a JFR `hot-methods` view surfaced this directly — see
`.local-run/jfr/<timestamp>/order-management-service/hot-methods.txt` from
that run for the raw evidence style.

**Confirmed gap (2026-08-13)**: `scripts/run-local.sh` and
`scripts/run-infra-docker.sh` — the two documented "supported" ways to bring
up the stack (see root `README.md`) — do **not** set this env var, so they
inherit the bad default. Only `scripts/perf/reset-venue-state.sh` has been
fixed to default to `yielding`.

**Action required**: always export `EXCHANGE_CORE_WAIT_STRATEGY=yielding`
when starting or restarting `order-management-service`, by hand or in any
script. Before trusting a latency/capacity benchmark result, verify the
running process actually has it set rather than assuming a startup script
got it right:

```bash
ps eww -p $(cat .local-run/pids/order-management-service.pid) \
  | tr ' ' '\n' | grep WAIT_STRATEGY
```

## Re-seeding a portfolio balance has no effect until the outbox backlog is cleared

- **Where**: `emporia_execution.exchange_core_portfolio_outbox` (execution DB,
  port `5437` in Mode 2), created by
  `order-management-service/src/main/resources/db/migration/execution/V1__create_portfolio_outbox.sql`

**Why**: exchange-core writes its *own* internal balance back to
portfolio-service through a durable outbox (`PortfolioOutboxPublisher` /
`PostgresPortfolioOutbox`, in the `exchange-core` library) — a background
job drains queued snapshot rows and `PUT`s them to portfolio-service's
`/internal/v1/portfolio-snapshots/{deliveryId}/{clientId}`. This table is
**not** part of `EXCHANGE_CORE_STORAGE_DIRECTORY` and is not touched by
deleting that directory. If a client's balance was ever driven toward zero
(e.g. by a long benchmark run), there can be a large backlog of queued
snapshot rows still carrying that old near-zero balance. Manually
`UPDATE`-ing `portfolio_balance.available_balance` looks like it worked
(the row shows the new value immediately) but gets silently overwritten
back to the stale value minutes later once the backlog drains through.

**Confirmed 2026-08-13**: one client's outbox had **161,378** queued rows
(104,111 `PENDING`, 43,964 `DEAD` from `HTTP 401` - an expired/invalid
auth token that was never refreshed for a long stretch, a separate bug not
yet investigated). A fresh `UPDATE ... available_balance = 999999999999`
was silently reverted to `0` within seconds by this backlog draining.

**Action required**: `scripts/perf/reset-venue-state.sh` now clears this
table as part of its reset (added 2026-08-13) — prefer it over resetting
storage by hand. If seeding a balance manually outside that script, clear
the backlog first:

```bash
PGPASSWORD=admin123 psql -h localhost -p 5437 -U postgres -d emporia_execution \
  -c "delete from emporia_execution.exchange_core_portfolio_outbox;"
```

Then re-seed (`scripts/seed-portfolio-client.sh <username>`) and verify the
balance holds after a few seconds, not just immediately after the UPDATE.
