# Configuration Notes

Non-obvious configuration facts discovered through real incidents, kept here
so they don't have to be rediscovered. Add an entry when a config default
silently produces misleading results and the fix isn't obvious from reading
the code alone.

## exchange-core `wait-strategy` defaults to `blocking`, not a spinning strategy

- **Where**: `order-management-service/src/main/resources/application.yml`,
  key `emporia.execution.exchange-core.wait-strategy`
  (env override: `EXCHANGE_CORE_WAIT_STRATEGY`)
- **Default**: `blocking` (was `busy-spin`, briefly `yielding` in practice)

**Why**: the spinning strategies were sized for execution running in its own
process. Since the 2026-08 rework merged execution routing in-process into
`order-management-service`, exchange-core's internal threads contend with the
OMS Disruptor writer thread and `ShardedOrderDispatcher`'s shard threads for
the same cores.

**The reproducible effect is CPU burn, measured 5 times on an 8-core box with
the process fully idle:**

| | `yielding` | `blocking` |
|---|---:|---:|
| Whole-JVM CPU while idle | 422–490% | 72–74% |
| Threads spinning | 7 × ~68% of a core | none (only `oms-hotpath-1`) |

That is ~5 of 8 cores consumed doing nothing. `busy-spin` is worse again: JFR
showed 88.5% of all CPU samples inside busy-spin loops.

**The latency effect only appears once there is real queueing.** The spin does
not slow the work itself — it delays *when the single Disruptor writer thread
gets scheduled*. Measured at 120 orders/sec, submitting directly to
order-management-service, clean book, 7,201 orders each, 100% accepted:

| Layer (p50/p95/p99) | `yielding` | `blocking` |
|---|---:|---:|
| HTTP submit total | 3/62/**224** ms | 2/34/**134** ms |
| └ Disruptor ring queue wait | 1/50/**201** ms | 1/22/**112** ms |
| └ Disruptor handler | 2/8/17 ms | 2/7/14 ms |

Note the difference is almost entirely queue wait; the handler is the same.

**Below ~100 orders/sec the two are indistinguishable.** Do not expect to see
this in a low-rate run: at 60–100/s, run-to-run variance swamps it. Two
*identical* `yielding` runs at 60/s produced p99 of 650 ms and 30 ms — a 21x
spread — so any single-run A/B at those rates measures noise. An earlier
version of this document claimed a 4x latency win for `blocking` based on
exactly that mistake; it was withdrawn after four repeated runs showed the
configs trading places.

**Verify before trusting a benchmark** — do not assume a startup script got it
right:

```bash
ps eww -p $(cat .local-run/pids/order-management-service.pid) \
  | tr ' ' '\n' | grep WAIT_STRATEGY
```

**OMS's own ring follows the same default.** `emporia.disruptor.wait-strategy`
(env `EMPORIA_DISRUPTOR_WAIT_STRATEGY`) also defaults to `blocking`, for the
same reason: its writer thread `oms-hotpath-1` was measured spinning at ~89% of
a core, and it has not had a core to itself since the merge.

## A load test through the gateway cannot exceed 100 orders/sec per retail user

- **Where**: `gateway/.../filter/OrderRateLimiterGatewayFilterFactory.java`

**Why**: the limiter is a per-identity token bucket whose rate comes from the
JWT `tier` claim, and **the tier branches ignore the configured values**:

| `tier` claim | replenish | burst |
|---|---:|---:|
| `institutional` | 5,000/s | 10,000 |
| `retail` | 100/s | 200 |
| absent | `emporia.gateway.order-rate-limiter.*` (default 20/s, 40) | |

The bootstrap `admin` user has `tier=retail`, so every k6 run that goes
through the gateway is capped at 100/s no matter what `ORDER_PATH_RATES` says.
Over the cap the gateway returns **429**, which `scripts/perf/order-load.js`
buckets as a *business rejection* (any 4xx) — indistinguishable in its output
from a genuine risk rejection.

**Confirmed 2026-08-14** at 120/s offered through the gateway: 2,081 × 201,
1,501 × 429, body `gateway_rate_limited: too many order commands`, while
order-management-service's own `http_server_requests` recorded **only the
2,081** — the rejected ones never reached it. The same workload submitted
directly to `http://localhost:8086/orders` completed 7,201/7,201 at 100%.

This invalidated an earlier reading of the capacity sweep: 80/s clean, 100/s
~13% "business rejections", 120/s ~39% looked like a knee in the order path
and was first attributed to the test account exhausting its buying power. It
was the gateway all along — the account balance (~1e12 scaled, ~1,030 consumed
per resting order) was never close to exhausted.

**How the benchmark handles it** (as of 2026-08-14): `order-path-capacity.sh`
keeps every request going through the gateway — a benchmark that skips the edge
is not measuring the path orders actually take, and loses authentication from
the measurement — and instead promotes the benchmark user to the
**institutional** tier (5000/s) before minting tokens. The limiter still runs in
front of every request; it just stops being the ceiling. Controlled by
`BENCH_TIER` / `AUTH_ADMIN_URL`; this is a persistent change to that account.

Only `INSTITUTIONAL` raises the limit. `INTERNAL` and `VIP` are not special-cased
in the filter and fall through to the configured default (20/s) — *lower* than
retail.

Verified at 120/s through the gateway after the change: 5,344 accepted, **0%**
business rejections, versus ~39% 429s before.

## The gateway's order bulkhead is a concurrency limit, and it defaulted to 25

- **Where**: `gateway/src/main/resources/application.yml`,
  `resilience4j.bulkhead.instances.orderCommands.maxConcurrentCalls`
  (env `EMPORIA_GATEWAY_ORDER_BULKHEAD_MAX_CONCURRENT`)
- **Was**: unset, so Resilience4j's default of **25** applied
- **Now**: 200

**Why it is easy to misread**: the `orderCommands` route carries three limits
that constrain different things, and only the first is a rate:

| Filter | Limits | Value |
|---|---|---|
| `OrderRateLimiter` | orders **per second** | per tier (retail 100/s) |
| Bulkhead (implicit) | orders **in flight at once** | 25 (default) → 200 |
| `CircuitBreaker` | trips on failure rate | 75% of last 100 calls |

In-flight count is `rate x latency`, so it is *latency*, not offered rate, that
pushes into the bulkhead. At 120 orders/sec, a p95 of 200ms already means ~24
concurrent calls — at the default ceiling.

**The failure amplifies rather than sheds.** Measured at 120/s on the default,
745 of 3,601 requests failed, but only **144** came from the bulkhead itself;
the other **601** came from the circuit breaker opening behind it and refusing
everything for 5s — while p50 was 7-9ms and the system was nowhere near
overloaded. The mechanism: a `BulkheadFullException` completes in **0 ms** and
is recorded into the circuit breaker's 100-call window immediately, while a
successful call is only recorded when it finishes. During a latency spike the
window therefore fills with instant rejections far faster than with slow
successes, and the failure rate crosses 75% even though most requests would
have succeeded.

**Measured after raising it to 200** (clean book, ~7.2k resting orders, 120/s
for 40s): 4,801/4,801 accepted, zero 503, zero circuit-breaker `ERROR` or
`NOT_PERMITTED` events, p50 8ms / p95 376ms.

**The amplification is fixed separately**, because raising the ceiling only
makes hitting it rarer — no ceiling is high enough forever, and what matters is
how the system behaves when it *is* hit. The `orderCommands` circuit breaker now
carries `ignoreExceptions: [io.github.resilience4j.bulkhead.BulkheadFullException]`,
so a full bulkhead is neither a success nor a failure to the breaker; it still
opens for genuine downstream failures.

Measured A/B with the bulkhead forced down to 5 to guarantee it is hit,
120 orders/sec for 30s, same book:

| | Without `ignoreExceptions` | With |
|---|---:|---:|
| Accepted (201) | 2,942 | **3,483** |
| Failed (503) | **659** (18.3%) | **118** (3.3%) |
| Bulkhead rejections | 37 | 118 |
| Blocked by open circuit | **622** | **0** |
| Circuit opened | 3 times | never |

37 rejections took 622 unrelated requests down with them — a 17x amplification.
With the flag the bulkhead sheds exactly its excess and nothing else. (The
rejection count is *higher* with the fix only because without it the open
circuit stopped traffic from reaching the bulkhead at all.)

**Gotcha when overriding by environment variable**: `resilience4j` instance
names are map keys and case-sensitive.
`RESILIENCE4J_BULKHEAD_INSTANCES_ORDERCOMMANDS_MAXCONCURRENTCALLS` binds a *new*
instance named `ordercommands` and silently does nothing to `orderCommands` —
it looks exactly like the setting having no effect. Use the env var declared in
`application.yml` above, or `-Dresilience4j.bulkhead.instances.orderCommands.maxConcurrentCalls=...`.

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
