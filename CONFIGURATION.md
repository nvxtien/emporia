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

## The live-order store is bounded by the heap, and the bound is derived not guessed

- **Where**: `OrderStateCache`, `LiveOrderStoreWarmup`, measured 2026-08-17

`OrderStateCache` holds every live order rather than a bounded cache of them, so
the parent-to-children and desk-to-orders indexes can answer without a database
query. What stops it growing is `emporia.orders.live-max`, and reaching it
**refuses a new order with 429** rather than evicting one already held - an
evicted live order is one the service can no longer see, answer for or cancel,
and nothing would say so.

**The default is derived from the heap, from two measured points rather than a
round number.** On a 4 GB heap, 188,666 live orders ran and 333,379 died with
`OutOfMemoryError` during the startup load. At a 20 KB budget per live order the
derived cap is 209,715 - inside the range that worked, below the one that did
not:

```
Live-order store capacity derived from the heap: 209715 orders
  (4096 MB max heap at 20 KB budget per live order)
```

The 20 KB is a **system budget, not an object size**: the same order is also in
exchange-core's book, in this store's two indexes and in the deduplication
index, all sharing one heap.

**Nothing sets `-Xmx`, deliberately.** The JVM takes a quarter of physical RAM
and the cap follows it, so a bigger host raises the ceiling with no
configuration. Pinning a heap size would break that.

**Hitting the cap during the startup load leaves the set incomplete and says
so**, rather than marking a set it could not finish:

```
Live-order store hit its capacity after 138774 order(s);
  the live set is INCOMPLETE and lookups stay on Postgres.
```

That is the designed degraded mode: the indexes refuse to answer, every lookup
falls back to the database, and behaviour is exactly what it was before the
store existed. Slower, and correct.

**But "slower, and correct" understates what a full store does to intake, and
that is worth stating plainly.** The startup load stops *at* `liveOrderMax`, so
a book larger than the cap leaves `atCapacity()` true before the port opens -
and `create()` refuses on exactly that condition. The service then declines
**every** new order, not merely the ones that would have overflowed:

```
HTTP 429 {"status":429,"error":"Too Many Requests","path":"/orders"}
```

Confirmed 2026-08-17 with 404,820 live orders against a 209,715 cap: a k6 run at
20 orders/sec recorded **0 accepted, 100% business rejections** and aborted on
its own guard in 15 seconds. The incomplete live set and the refusal to accept
anything are the same condition seen from two sides, and only the first is
logged.

**Read the body, not just the status.** The gateway's rate limiter also answers
429, with body `gateway_rate_limited: too many order commands` and an
`X-RateLimit-Reason` header. A 429 carrying `"path":"/orders"` and neither of
those is the store, and the two have opposite fixes - raise the tier for the
first, shrink the book for the second.

**Only orders leaving clears it.** `cancel()` deliberately does not check
`atCapacity()`, so cancellation still works when submission does not - which is
the property that makes the state recoverable at all. Cancelling 180 orders
dropped the store below the cap and the next submit returned 201. There is no
operator switch; the book has to shrink, whether by cancellation through the API
or by `scripts/perf/reset-venue-state.sh`.

**The load walks by key, not by offset, and the difference is large.** An offset
page has to order the whole live set before it can skip into it, so every page
paid for a full sort - at 189,000 live orders `EXPLAIN` showed an external merge
spilling **31 MB to disk and 1,435 ms for a single page**, repeated 38 times.
Keyset paging index-scans the primary key from where the last page stopped: no
sort, 240 ms per page, cost flat as pages advance.

| live orders | paging | load time | per order |
|---:|---|---:|---:|
| 188,666 | offset | 43,495 ms | 0.231 ms |
| 333,379 | **keyset** | **35,523 ms** | **0.107 ms** |

A set 1.77x larger loaded **faster**, which is what replacing a quadratic cost
with a linear one looks like.

**The load runs in `@PostConstruct`, before the port opens**, so it blocks
startup by that much. `DedupIndexWarmup` can load after the application is ready
because its filter only ever answers "never seen" and a command arriving mid-load
still falls through to the database. This one cannot: a fill could update an
order in memory while the loader still held the older row, and writing that row
over the newer one would lose the fill.

## A health check cannot tell your service from the one already on that port

- **Where**: `scripts/lib/run-common.sh`, `wait_http_health`, found 2026-08-17

`run-infra-docker.sh` starts each service, then polls its actuator URL until
something answers. When a previous stack is still running, the service just
started **fails to bind, the old one answers the poll, and the launcher prints
its success banner**. Every measurement afterwards is against whatever code that
older process loaded, which is not necessarily what is checked out now.

Observed with all eight services at once: each log held

```
APPLICATION FAILED TO START
Description: Web server failed to start. Port 8086 was already in use.
```

while `:8086` was served by a process started eleven hours earlier, and the
launcher reported the stack up.

**Checking that the started process is still alive does not catch this.** The
failed JVM does not exit: exchange-core starts non-daemon threads during context
refresh, before the web server binds, so the process lingers after the context
fails - alive, holding a couple of hundred MB, answering nothing. That is what a
"process not responding" in Activity Monitor usually is here.

`wait_http_health` now resolves the pid listening on the URL's port and requires
it to descend from the pid this run recorded, failing with

```
order-management-service is NOT the process this script started.
  :8086 is held by pid 90120 (started Mon Aug 17 04:20:20 2026)
  this run started pid 69052 - it most likely failed to bind
```

**Two things follow for cleanup.** `scripts/stop-services.sh` reads
`.local-run/pids/`, which after such a run names the processes that died rather
than the ones still serving - so it kills the corpses and leaves the stack up.
Stop by port instead. And when the ownership check cannot run - no port in the
URL, no pid recorded, no `lsof` - it says so on stderr rather than passing
quietly, because a check that silently succeeds when it cannot run is the same
trap it exists to close.

## p99 is not measurable on a developer machine, and four conclusions died proving it

- **Where**: six runs of `scripts/perf/order-path-capacity.sh` plus a controlled
  snapshot experiment, measured 2026-08-17

Across six runs at 200/s that differed in **one** deliberate variable, and two
more that differed in none:

| variable | p50 | p99 |
|---|---:|---:|
| snapshot every 60 s (3 runs) | 3.52 - 3.90 ms | **55 - 862 ms** |
| snapshot every 600 s (3 runs) | 3.41 - 3.84 ms | **600 - 1,465 ms** |

**p50 varied by 14%. p99 varied by 15.8x within one arm**, and the arm with no
snapshot in the measurement window was *worse*. Nothing causal can be read from
that, which is the point.

**Four mechanisms were proposed and each was refuted by the next measurement**:
unaccounted time inside `handle()` (the ring stall diagnostic reported **0 ms
CPU** during the stall - the writer was not busy, it was not scheduled); lock
contention on a desk index (the build without that index stalled identically);
p99 scaling with book size (105,000 resting orders produced 23 ms); and the
synchronous exchange-core snapshot (the table above).

The only unrefuted evidence is direct rather than correlational, from JFR:

```
Ring queue wait 53 ms: writer idle 53 ms before this event,
                       using 0 ms CPU across that gap
```

The writer is **3.2% utilised** - 1.9 s of work across a 60 s run - and the tail
appears when the operating system does not run it. This machine hosts 14 JVMs,
six Postgres containers, Prometheus, Grafana, Tempo, an OTel collector, a Vite
dev server, **and k6 itself**. That last one is the trap LMAX names explicitly:
*separate load generation from measurement*.

**So: quote p50, and treat any p99 from this machine as unusable.** A real tail
number needs k6 on a separate host. Until then a controlled A/B is still valid -
noise hits both arms - but an absolute latency figure is not.

## Order path capacity: the knee is 300 orders/sec

- **Where**: `scripts/perf/run-baseline.sh probe`, measured 2026-08-17 against
  ~200,000 resting orders. Supersedes the 200-250 estimate below.

`run-baseline.sh` detects the knee itself rather than leaving it to be read off
a table:

| offered | accepted | p50 | p99 |
|---:|---:|---:|---:|
| 200/s | 12,001 | 4 ms | 32 ms |
| 250/s | 15,000 | 4 ms | 33 ms |
| 300/s | 18,001 | **5 ms** | 42 ms |
| 400/s | 23,993 | **6 ms** | 181 ms - **degraded** |

**p50 is the signal that matters here**, because it is a median over 18,000+
samples rather than a tail. It sits flat at 3.4-3.9 ms through 250/s and then
moves: 5.12 ms at 300/s, 6.95 at 400/s, 7.07 at 500/s, 8.51 at 600/s. A median
that moves means the queue is no longer empty between commands.

400/s is also the first rate all night to report a **non-zero infrastructure
failure rate** (0.03%) and to accept less than it was offered - two signals
independent of p99, which is what makes the knee credible when p99 alone is not.

**The ceiling is above 600/s and was not found.** 35,964 of 36,000 were accepted
at 600/s with zero rejections; the 36 missing look like k6 not issuing them
rather than the service refusing, which means measurements above 600/s describe
the load generator.

**Why this differs from the 200-250 estimate below.** That one rested on p99
degrading at 250/s (148 ms and 124 ms). Tonight 250/s measured 33 ms with a book
sixteen times deeper. The earlier reading was not careless - it was a
conclusion drawn from a metric this machine cannot measure.

## Superseded: order path capacity: the knee is between 200 and 250 orders/sec

- **Where**: `scripts/perf/order-path-capacity.sh`, measured 2026-08-16 after
  Kafka removal, the execution-service merge and the branch-A decision

Through the gateway, full edge path, 60 s per rate, benchmark identity promoted
to the institutional tier so the rate limiter is in the path but not the
ceiling:

| offered | position | p50 | p95 | p99 |
|---:|---|---:|---:|---:|
| 120/s | first | 3.56 ms | 9.37 ms | 47.51 ms |
| 150/s | second | 3.43 ms | 8.31 ms | 28.52 ms |
| 200/s | **first** | 3.24 ms | 5.51 ms | **15.62 ms** |
| 250/s | second | 5.00 ms | 54.24 ms | **148.24 ms** |
| 250/s | **first** | 4.23 ms | 29.56 ms | **123.95 ms** |
| 200/s | second | 4.30 ms | 20.32 ms | **52.41 ms** |

Every rate was accepted in full - 54,000 orders across the 200/250 runs, 0%
business rejections, 0% infrastructure failures, and all three duplicate
counters still at zero.

**250/s degrades in both positions**, so that is the workload and not an
artifact of ordering. 200/s stays comfortable even from the unfavourable
position. The knee is between them; the ceiling has not been found.

**Order within a run matters, and it is not JIT warm-up.** Running the pair
forwards and backwards was meant to cancel warm-up and instead ruled it out:
whichever rate ran *second* was slower, both times. Warm-up would make the
second one faster. The likelier explanation is accumulated state - the first
rate leaves 12,000-15,000 orders resting in the exchange-core book and as many
rows behind them, so the second runs against a deeper book. One sample per
configuration, so this is the better explanation rather than a demonstrated one.

**p99 is ring queue wait, not processing cost.**

| run | mean queue wait | max |
|---|---:|---:|
| 200 then 250 | 0.229 ms | 64.9 ms |
| 250 then 200 | 0.280 ms | 150.3 ms |

The mean is near zero - the single writer keeps up. The max tracks p99 closely
(150.3 against 148.24, 64.9 against 52.41), so the tail is the writer stalling
and everything queued behind it waiting, exactly the multiplication described
above. What causes the stalls is not established; exchange-core's synchronous
per-operation checkpoint at a 60 s interval is the standing suspect, and is the
same one recorded against the unexplained 890 ms blip at 40/s.

**There is no latency requirement anywhere in this project** - no SLO, no
budget, no alert threshold on latency; the only alerts are the gateway's rate
limit and circuit breaker. So these numbers describe the system without saying
whether it is fast enough, and "fast enough" has no answer here yet. Note also
that the figures are for the REST path through gateway authentication, which is
what quadrants (1) and (2) use; FIX order entry does not repeat that per
message and should not be predicted from them.

**A metadata field was wrong until now.** The script recorded
`Exchange-core journaling: false` by defaulting an unset environment variable to
`false`, while the service log said `journaling=true`. Every earlier run was
labelled unjournalled while journalling. Corrected to default to `true`, matching
`application.yml`; runs recorded before 2026-08-16 carry the wrong label.

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

## Anything on the single writer thread is multiplied by the queue behind it

- **Where**: `DisruptorOrderPipeline`'s handler, and everything it calls -
  `logAhead`, `OrderCommandHandler.handle`, `wal.markSafePoint`

Every order command passes through one thread, so a fixed per-event cost there
does not add to latency, it multiplies. Measured at 120 orders/sec: 0.6 ms per
command of tracing overhead showed up as several hundred milliseconds of ring
queue wait, because each command paid it and every command behind it waited.

**Measured breakdown of the writer's own work** (means, 120/s, 180 s):

| | mean | share |
|---|---:|---:|
| handler total | 3.047 ms | 100% |
| `OrderCommandHandler.handle` | 3.029 ms | 99.4% |
| WAL `logAhead` | 0.011 ms | 0.3% |
| `wal.markSafePoint` | 0.001 ms | 0.0% |

The WAL is not the cost, which is worth knowing before reaching for the usual
LMAX advice of moving journalling to a parallel handler - here that buys 0.3%.

**Micrometer `Observation` is expensive here, and sampling does not help:**

| | mean handler |
|---|---:|
| `Observation`, tracing 100% | 3.047 ms |
| `Observation`, tracing 1% | 2.764 ms |
| `Observation`, tracing off | 2.437 ms |
| `Timer`, tracing 100% | 2.521 ms |

0.53 ms of the 0.61 ms is the Observation machinery - handler lookup, context,
key-values - which runs regardless of the sampling decision; only 0.08 ms is
recording the span. Head sampling therefore recovers little, and **tail sampling
recovers nothing**: deciding after the fact requires the span to record in full.
The hot path now uses a plain `Timer` with the same metric name and tags.

**Diagnosing a slow order**: `emporia.order.submit` still spans the whole
request and carries `order_id`; the per-layer timers say which layer; and
`emporia.disruptor.stall-threshold-ms` (0, off) logs long ring waits with the
same `order_id`, plus how long the writer was idle and how much CPU it used
across the gap - near-zero idle with a deep ring is a backlog, idle time not
spent on CPU is time it was not scheduled.

## Neither leader-election provider excludes a second machine

- **Where**: `HaProviderAutoConfiguration`, `LocalFileLeaderProvider`,
  `RedisRedlockLeaderProvider`, `emporia.ha.provider`

Order deduplication is correct if and only if exactly one instance accepts
orders. `DisruptorOrderPipeline` asks `LeaderElectionService.isPrimary()` and
refuses with 503 when the answer is no, so the assumption is checked rather than
assumed. What it is checked against is the problem.

| `emporia.ha.provider` | what it does |
|---|---|
| `local-file` (default) | takes a real `FileLock` via `tryLock()`. Excludes a second process on one filesystem. Its own javadoc scopes it to development. |
| `redis` | **touches no Redis.** An in-process `AtomicBoolean` that returns true unconditionally. |

`RedisRedlockLeaderProvider` names Redlock and logs a lock key, but there is no
Redis client anywhere in the build - no `redisson`, `lettuce`, `jedis` or
`spring-boot-starter-data-redis` in any pom. `tryAcquireOrRenewLease` reads:

```java
boolean acquired = isLeaderHeld.compareAndSet(false, true) || isLeaderHeld.get();
```

The first call sets it, every later call reads it back, and both answer true. Its
unit test asserts exactly that, so the behaviour is pinned as correct.

This is a known placeholder rather than an oversight - the provider was never
finished - so treat it as unimplemented, not as something to debug.

So switching to the production-sounding provider does not extend the guarantee
across machines - it **removes** the one-machine guarantee the file lock gives,
and every node believes it is primary. The order-management service sets no
`emporia.ha` block at all and no script or compose file overrides it, so it runs
on `local-file` today by falling through `matchIfMissing = true`.

**What this costs.** Two instances accepting orders means two independent
deduplication indexes, each certain that ids the other has handled are new.
`emporia.oms.dedup.duplicate_reached_db` and
`duplicate_order_reached_db` would catch it - both are database-side, so they see
writes from every instance - which makes them the detector for this too, not just
for a filter bug.

## entity_version froze at 0 when order writes moved to raw JDBC

- **Where**: `TradingOrder.recordRevision`, `OrderStateCache.put`,
  `AsyncDbWriter.flushOrdersJdbc`, `OrderCommandHandler.modify`

`entity_version` carries JPA's `@Version`, so Hibernate used to increment it on
every save. Order writes then moved to raw JDBC in `AsyncDbWriter`, which
bypasses Hibernate entirely, and nothing incremented it any more. Every order
sat at version 0 for its whole life.

The visible consequence was in `modify`:

```java
require(command.expectedVersion() != null && order.getVersion().equals(command.expectedVersion()),
        409, "Order changed since it was loaded; refresh before modifying it");
```

The API returned version 0, callers echoed 0, and the comparison passed every
time. **An optimistic-lock guard the API advertises had silently stopped
firing**, and no test caught it because the tests that touch versions mock
`orders.save`, which is the path production no longer uses.

The revision is now stamped in `OrderStateCache.put`, the single funnel every
committed state change passes through, and `@Version` is gone from the entity.

Optimistic locking was not wanted here and was not working here. Every order
state change runs on the single Disruptor writer thread, so the race it guards -
a venue fill and a user cancel updating one row at once - cannot occur, and the
writes that would carry the guard leave through raw JDBC where Hibernate is not
involved. Keeping the annotation cost two things instead:

- Spring Data decides new from existing by `version == null` when the entity has
  a version attribute. `TradingOrder`'s constructor assigns 0, so **every
  `save()` of a brand-new order took the `merge()` path** - a select, no insert,
  then an optimistic-lock error for a conflict that never happened. Confirmed by
  SQL logging: a `select ... where id=?` and no `insert into trading_order`.
- The version incremented twice on the repository path, once in
  `recordRevision()` and again at flush, against once on the hot path.

Neither showed up because `orders.save`/`saveAll` is reachable only through
`AsyncDbWriter`'s fallback, which needs `jdbcTemplate` to be null - and Spring
injects it as a required constructor argument. The path is test-only.

**Expect more 409s on `PUT /orders/{id}`.** Every state change advances the
revision, fills included, so a caller that reads an order and modifies it while
it is filling now gets "Order changed since it was loaded" where it previously
succeeded. That is the guard working. The modify itself was never unsafe - it
re-validates the new quantity against live traded quantity - so what the caller
lost was a decision made on a stale read, and the fix is to re-read and retry.

They are 409s now. Until this was found they were **502 Bad Gateway**: a refused
command carries its status and reason and no payload, and the controller fell
through to parsing that absent payload as an `OrderView`, failed, and answered
502. Every domain rejection did this - order already exists, pending
cancellation, create incomplete, order not found, risk checks - so a caller
could not tell a refusal from an outage, and retrying was the natural response
to what it was told. At the edge it was worse: 502 is in the order route's
circuit-breaker `statusCodes`, so correct refusals counted as downstream
failures and enough of them would open the breaker and refuse every order.
`scripts/perf/order-dedup-check.sh` covers both this and the idempotent replay.

## The write-ahead log recovers process death, not machine loss

- **Where**: `MemoryMappedWalLogger`, `emporia.wal.file-path`,
  `emporia.async-db-writer.flush-delay-ms`

An order is answered 201 before it reaches PostgreSQL - the write is enqueued
and a batch flush persists it later. The write-ahead log covers that window, and
it covers it for one kind of failure only.

`append` writes into a memory-mapped region and does not `force`. Those pages
belong to the operating system, so they survive the process dying - `kill -9`, a
JVM crash - which is what `scripts/perf/crash-recovery-check.sh` exercises and
what the log is genuinely good for. They do not survive the machine dying.

**The exposure**, stated with numbers rather than left as a caveat:

| | |
|---|---|
| flush cadence | 10 ms (`flush-delay-ms`) |
| at 120 orders/sec | one or two orders in the window |
| those orders | already answered 201, and may already exist at the venue |
| what notices | **nothing** |

The last row is the uncomfortable one, and it needs stating precisely because
it is easy to get wrong in both directions.

**Order reconciliation walks both directions.** The `reconciliation` actuator
endpoint takes every order this service holds as LIVE or PARTIALLY_FILLED and
asks the venue whether it knows of it, so drift surfaces by inspection instead
of as an "unknown lifecycle order" on the next command. It then walks back the
other way, reporting orders resting at the venue that this service has no record
of - `ReconciliationDesyncDetectionTest
.detectsGhostOrdersOnVenueThatDoNotExistInOms` covers it.

*An earlier version of this section said nothing walked the venue-to-service
direction, and gave the reason: enumerating every symbol's resting book is not
something the venue's per-client report API offers cheaply. That reasoning was
about enumerating by symbol; the endpoint sidesteps it by enumerating per client,
using `ExchangeCoreVenue.openOrderIds(clientId)`. The text was not updated when
that landed.*

**What is still not covered is the client set.** The clients it asks about are
derived from the orders this service holds:

```java
Map<Long, List<OrderView>> byClient = liveOrders.stream()
        .collect(Collectors.groupingBy(...clientId));
```

So the venue is only queried for clients that already have a live order **here**.
A client whose only live order is the lost one never appears, and their ghost is
never found - which is exactly the durability failure above. `create` writes its
row before dispatching, but the write is asynchronous; if it never lands because
the machine died inside the 10 ms window, the venue holds an order this service
has no record of, and if that was the client's only order, reconciliation cannot
see it either.

Closing it means sourcing the client set from something independent of live
orders - the distinct owners in `trading_order` including terminal ones is
enough, and needs no new service dependency.

**The external-venue direction remains absent.** `FixExecutionVenueGateway` has
no `openOrderIds` equivalent, so none of the above applies when Emporia routes
out rather than internalises. It matters less there: the other venue keeps its
own records, and an orphan is recoverable by asking them.

**Position and balance reconciliation does not exist.**
`emporia-reconciliation` has been deleted: its two services were never called
from production, were never scanned into the context, and queried
`user_portfolio_positions` and `user_portfolio_accounts` - tables no migration
creates and which exist in none of the databases. Each caught `Exception` and
returned `BigDecimal.ZERO`, logging at `debug`, so a query against a table that
is not there read as a flat position and, where the engine also held zero,
counted as **matched** and reported `isConsistent = true`.

**Why there is no fsync on the append path.** A periodic `force` buys nothing:
PostgreSQL fsyncs on commit and the writer flushes every 10 ms, so for this log
to add durability against machine loss it would have to become durable *sooner*
than the database - an fsync per command, on the single Disruptor writer thread,
where a blocking call multiplies by the queue behind it rather than being
absorbed. That is the cost the hot path exists to avoid.

**The alternative, recorded rather than taken**: append on the writer thread and
have a separate thread force and only then complete the HTTP response - group
commit with a durable acknowledgement. It never loses an acknowledged order and
adds the force interval to every submit. The exposure above is accepted
deliberately instead; revisit this if the requirement becomes "an acknowledged
order survives machine loss".

## The web server starts at MAX_VALUE - 2048, and the venue used to start after it

- **Where**: `ExchangeCoreExecutionVenueGateway.getPhase()`,
  `StartupReconciliationGuard`, measured 2026-08-17

Spring Boot's `WebServerStartStopLifecycle` returns **2147481599**, which is
`Integer.MAX_VALUE - 2048`. Read out of the framework, not assumed - the first
attempt at a pre-traffic check assumed `MAX_VALUE - 1`, put itself at
`MAX_VALUE - 512`, and ran **twenty-four seconds after the port opened** while
its own unit test asserted it would not.

Lower phase starts first, so the venue gateway at `MAX_VALUE - 1024` was also
starting *after* the port. Every restart had a window in which the service
accepted connections while the matching engine had not yet rebuilt its lifecycle
projection. Nobody chose that: the phase was picked relative to default-phase
beans, which it is correctly above, and the web server was never in the
comparison.

**Why it could not simply be moved.** `start()` rebuilt the projection from
`TradingDataClient.recoverable()` - an HTTP call to *this same process*. Anything
that needs the port cannot run before the port, so the venue was pinned after it,
and any check needing the venue was pinned after that. The gateway now reads the
same rows in process through `LiveDirectOrders`, and the ordering constraint
disappears with the HTTP hop.

```
MAX_VALUE - 3072   venue gateway starts, lifecycle rebuilt   22:38:00
MAX_VALUE - 2560   startup reconciliation                    22:38:18
MAX_VALUE - 2048   Tomcat binds :8086                        22:38:19
```

`LiveDirectOrders` exists so the two callers that need "orders the venue is
answerable for" - the recovery endpoint and the startup check - cannot drift
apart. A guard whose query differs from the one that fed the venue would report
disagreements it had invented itself.

**The startup check warns by default rather than refusing.** Missing orders are
a correctness failure and `startup-policy=refuse` is available, but refusing
makes the service un-startable exactly when somebody is recovering it, and the
measurement above shows an ordinary hard kill reaches that state. Ghosts never
refuse: a service that will not start until someone clears 61,829 of them by
hand is worse than one that says so loudly.

## The venue's journal does not recover process death, and the two logs disagree

- **Where**: `exchange-core` `DiskSerializationProcessor`,
  `EXCHANGE_CORE_SNAPSHOT_INTERVAL`, measured 2026-08-17

The section above is about order-management's own write-ahead log and is
correct: it survives `kill -9`. **The matching engine's journal does not**, so
`kill -9` leaves the two sides of one process disagreeing about which orders
exist - order-management fully recovered, the venue quietly short.

**Reproduced deliberately.** Run load so the window exists only in the journal
(the checkpoint id must not move), then kill without a shutdown checkpoint:

```
BEFORE   checked=96,425  missing=0      ghosts=61,829   checkpoint=959
load     3,000 accepted, 1,465 cancels; checkpoint still 959
kill -9
AFTER    checked=99,152  missing=2,416  ghosts=61,829   checkpoint=959
```

**2,416 orders order-management holds as LIVE are absent from the matching
engine.** They are durable in PostgreSQL, they were answered 201, and they will
never fill. Ghosts did not move, so cancellation is not involved - the losses are
submissions.

**Why a kill perforates rather than truncates.** Journalling is a Disruptor stage
running *in parallel* with risk and matching, not in front of them, so the
journal is not a prefix of what the engine did. A hard kill leaves holes, and
recovery reports them and carries on:

```
WARN DiskSerializationProcessor : Sequence gap 0->20 (20)
WARN DiskSerializationProcessor : Sequence gap 28->37 (9)
855 gaps, 1,740 commands skipped, largest 20, and zero ERROR lines
```

The engine then opens for trading on a book it knows was rebuilt from an
incomplete journal. **Whether that should be a warning rather than a refusal to
start is the open question**, and it is in the forked dependency, so it is
answerable.

*Some of those gaps may be benign* - read-only commands are not journalled and
would leave legitimate holes. The gap count is evidence of the mechanism, not a
measurement of the loss. The loss is measured separately: `missing` went 0 →
2,416.

**Two things made this hard to see, and both are now fixed.**

`emporia.execution.exchange-core.journaling` was bound to **nothing**: the
gateway passed a literal `true` to `ProductionSimulationConfiguration`, so the
property had never done anything. `run-infra-docker.sh` set it to `false` and
printed `journaling: false` in its banner while the engine journalled anyway, and
`EXCHANGE_CORE_PRODUCTION_RUNBOOK.md` gated a staged production rollout on it -
*"Keep `EXCHANGE_CORE_JOURNALING=false` until `crash-recovery-check.sh` passes"* -
a gate that could not be opened or closed.

The log line was worse than useless, because something depended on it:

```java
log.info("... accounting-mode={} journaling=true retained-checkpoints={} ...")   // was
grep -q "journaling=true" .../order-management-service.log || fail "...proves nothing"
```

`journaling=true` was **hardcoded in the format string**, and
`crash-recovery-check.sh` verifies journaling by grepping for it. The check could
never fail, in either direction. Its own failure message says *"or this proves
nothing"*.

The property is now read (`@Value(...journaling:true)`), threaded to the engine,
and printed as a value, so the grep is falsifiable and the runbook's gate is
real. **The default is `true` everywhere**, including in `run-infra-docker.sh`,
because that is what the engine was already doing and what every measurement in
this document was taken under.

**The acceptance check could not fail, and now can.** `crash-recovery-check.sh`
exercised **two** resting orders. At that rate each command is its own Disruptor
batch and flushes promptly, so the journal never perforates and the test passed
every time. It now runs a load stage and asserts on a reconciliation *delta*, so
it works against an already-drifted book:

```
missing before 1479, after 2792, delta 1313
FAIL: the venue lost 1313 order(s) that order-management still holds as LIVE.
```

**It passed every one of its original assertions first** - journaling enabled,
lifecycle rebuilt, order B cancellable after recovery, no `unknown lifecycle
order`. The two-order version would have printed `PASS` on a run that had just
lost 1,313 acknowledged orders. It was not testing the wrong thing; it was
testing a different mechanism, correctly, and that mechanism was fine.

Two further defects in the check itself, both found by running it:

- It restarted with a plain `mvn spring-boot:run`. `agency` is the default
  profile (`!matching`) and carries no exchange-core dependency at all, so the
  restart brought up a build with no matching engine. The profile rename made
  that true silently - nothing failed until the script was next run.
- `DB_URL` defaulted to `localhost:5432/emporia`, which is not where any
  per-service database lives.

`k6` is now required rather than optional: without the load stage the check
cannot reproduce what it exists for, and a check that cannot fail is worse than
no check. Two independent runs have now measured the same loss - 2,416 of 3,000
and 1,313 of 3,001.

**Nothing surfaces any of this.** Order-management is internally consistent, the
client has a 201, and the only component that compares the two sides is
`ReconciliationEndpoint` - an actuator endpoint with no caller outside tests.
Reaching it takes an operator who already suspects the problem.


## Internalisation is a declaration about the entity, and it can refuse startup

- **Where**: `InternalisationPolicy`, `emporia.compliance.*`
  (env `EMPORIA_COMPLIANCE_*`), inactive by default

Matching client orders inside Emporia — `venue-mode: exchange-core` — is not
available to every operating entity. Listed securities in Vietnam must trade
through the Exchange, so a securities company there may not match client orders
internally at all; routing to an external venue is the only path.

Doing it anyway is not a bug that degrades service. It is trading the firm
cannot lawfully do, and every order it touches is already done by the time
anyone reads a dashboard. There is no safe way to discover it late, so a
deployment that configures an internalising venue mode without permission
**refuses to start** — the same trade the deduplication horizon guard makes.

```yaml
emporia.compliance:
  jurisdiction: VN                    # supplies the default below
  internalisation-permitted:          # blank = take the jurisdiction default
```

**The code does not claim to know the law.** `jurisdiction` supplies only a
*default* for `internalisation-permitted`, so the safe answer is the one you get
without thinking about it. The authority is the explicit declaration, because
rules change and a stale legal claim in source blocks something lawful exactly
as silently as a missing one permits something unlawful. An entity licensed to
internalise says so in configuration an auditor can read, and that declaration
is a legal statement about the entity rather than a tuning parameter.

The jurisdiction table currently holds one row, `VN`. It is not legal advice and
adding a row needs counsel, not a grep.

**Leaving `jurisdiction` unset leaves the guard inactive**, which preserves
every deployment and development machine that predates it. State the cost
plainly: an unspecified jurisdiction buys no protection at all. The guard only
works for deployments that declare where they are.

### Two artifacts, `agency` and `matching`, and the jar proves which is which

The build produces one of two artifacts, and they are different **products**
rather than two configurations of one:

| | `agency` (**default**) | `matching` (`-Dmatching`) |
|---|---|---|
| product | equities (listed stock) | crypto |
| `exchange-core` | **absent** | packaged |
| matches internally | **cannot** | yes - B2C, client against Emporia's own capital |
| jar | `order-management-service-agency-<version>.jar` | `order-management-service-<version>.jar` |
| tests | 309 | 361 |

```bash
mvn package              # -> order-management-service-agency-<version>.jar
mvn package -Dmatching   # -> order-management-service-<version>.jar
```

Verified on the produced agency jar: **zero** `exchange/core2` entries and
**zero** `ExchangeCore*` classes, with `FixExecutionVenueGateway` intact so
external-venue routing still works. The 52-test difference is the excluded
classes' own tests.

**Why agency is the default.** Forgetting a flag must not hand you the artifact
that can trade the firm's own capital. The principle is stated in
`InternalisationPolicy` itself - *the safe answer is the one you get by not
thinking about it, and the unsafe answer has to be written down where an auditor
can see it* - and a `matching` default contradicted it. The two mistakes are not
symmetric:

| mistake | how it fails |
|---|---|
| build `matching`, deploy as equities | the jar **can** internalise. The startup guard catches it only if a jurisdiction was declared, and an unset jurisdiction leaves that guard inactive. **Silent.** |
| build `agency`, run as crypto | startup fails with a sentence naming the build and telling you to pass `-Dmatching`. **Loud.** |

**This axis used to be called `vn`.** It was built for one jurisdiction - listed
securities in Vietnam must trade through the Exchange - but the mechanism it
produced, an artifact that *cannot* match internally, turned out to be the
equities product itself. Vietnam is one deployment of the agency artifact, not
the reason for it. Jurisdiction stayed where it always was: runtime
configuration read by `InternalisationPolicy`, never a build flag.

Configuration can be changed after an audit; a binary can be hashed. That is
what the artifact buys over the startup guard, and why both exist.

Whether the gateway is on the classpath is a property of the build, so
`InternalisationPolicy` exposes it as an overridable seam rather than reading
the classpath inline. Inlining it made one branch untestable and, under the
agency build, made the artifact check fire ahead of every jurisdiction test in
the same class and mask it.

**Activated by property (`!matching` / `-Dmatching`), not by `activeByDefault`
or `-P`.** Maven switches off an `activeByDefault` profile the moment any other
`-P` is passed, so a `-P postgres-it` build would silently change which artifact
you get. Property activation composes instead of competing.
`mvn help:active-profiles` reports exactly one of `agency` or `matching` - never
both, never neither.

**`matching` is a flag, not a boolean.** Maven activates on the property being
*defined*, whatever its value, so `-Dmatching=false` **also** produces the
matching artifact. Omit the flag for agency; do not write `-Dmatching=false`.
Making it value-sensitive is worse: `-Dmatching=false` would then match neither
profile, dropping exchange-core without excluding the sources that import it,
and the result reads as a broken build rather than a wrong flag. The `-agency-`
in the artifact name is the safety net - the jar that cannot internalise is the
one you can identify by name.

**Five scripts pass `-Dmatching`** because they exercise the exchange-core path:
`run-infra-docker.sh`, `perf/matching-engine-benchmark.sh`,
`perf/reset-venue-state.sh`, `perf/first-request-check.sh`, and `local-ci.sh` -
which now runs `clean verify` **twice**, once per artifact. Until it did, the 52
tests excluded from the agency build never ran in CI at all.

Asking that artifact for `venue-mode: exchange-core` fails with a sentence
naming the build, not a `ClassNotFoundException` inside Spring. An obscure
failure invites a workaround.

**This does not replace the startup guard.** The guard still catches the case
the artifact cannot: deploying the wrong jar.

`emporia-journal` declared `exchange-core` and imported nothing from it. That
dead dependency is gone, which is also what kept it out of the VN artifact
transitively.

The resolved answer is published as an `InternalisationDecision` bean rather
than re-derived, because the quoting engine will need exactly it: posting a
two-sided price *is* internalisation, whether or not a client order ever takes
it.

## The dedup index answers from memory, and its horizon is a correctness bound

- **Where**: `RotatingDedupIndex`, `OrderStateCache`, `emporia.dedup-index.*`
  (env `EMPORIA_DEDUP_INDEX_*`), on by default

`OrderStateCache`'s two hot-path lookups - `existsById` for the duplicate-order
guard and `findProcessedById` for idempotency - used to read through to Postgres
on every order. Not "mostly misses": **zero cache hits in 14,402 orders**, because
a `CREATE` carries freshly generated ids so the read-through fires every time.
They cost 2.312 of the handler's 2.405 ms, on the single writer thread, where
that multiplies by the queue behind it.

A Bloom filter answers "never seen" from memory instead. Six alternating runs at
120 orders/sec, both orders of arm:

| | ring queue wait, mean | max | k6 submit p99 |
|---|---:|---:|---:|
| index on | 0.035 - 0.230 ms | 17 - 52 ms | 27 - 73 ms |
| index off | 1.872 - 5.712 ms | 248 - 479 ms | 106 - 361 ms |

**The horizon is not a performance knob.** Past it the filters report "never
seen" and `OrderStateCache` returns that answer *without consulting Postgres*, so
a command older than the horizon reads as new even though the database still
holds it. That is a false negative, and on this system a false negative is a
duplicate position.

### The horizon is a session count, not a duration

There is no `horizon` property. It is derived from the rotation schedule and the
number of generations retained, and rotation lands on the **start of each
trading session**:

```
session-starts:    06:00, 12:30      # sessions run 06:00-12:00 and 12:30-18:00
sessions-retained: 2
horizon:           6h30 + 17h30 = 24 hours
```

Only session *starts* are configured. Session ends do not affect rotation, so
holding them here would be configuration nothing reads and nothing would detect
as wrong.

**The rule when adjusting these per country:** retain as many generations as
there are session starts. The gaps between consecutive starts always sum to
exactly one day however unevenly the day is carved up, so that rule yields
exactly 24 hours for any country and any number of sessions, with no arithmetic
needed at the deployment site.

24 hours is not chosen for tidiness: it matches
`OrderStateCache.IDEMPOTENCY_KEY_TTL`, the window in which a repeated key is
honoured as a retry rather than treated as a new request.

**The service refuses to start if the horizon lands under that TTL.** Getting
this wrong has no symptom - not a slow path, not an error, nothing - until a
caller retries near the old bound and gets a second position, possibly months
later. Refusing to start converts a silent correctness bug into a loud
deployment one. The message names the computed horizon, the schedule and the
retention that produced it.

Two things about the arithmetic that are easy to get wrong:

- **The horizon is the sum of the retained gaps, not the shortest gap times the
  retention.** With 06:00 and 12:30 the gaps are 6h30 and 17h30, so the second
  formula reports 13 hours against a real 24. This is not cosmetic:
  `DedupIndexWarmup` loads exactly `horizon` worth of history from Postgres at
  startup, so under-reporting it leaves a window that survives a restart reading
  as "never seen".
- **The horizon is a floor, not an average.** Coverage is at its minimum in the
  instant *after* a rotation and grows until the next one. Reasoning from the
  average overstates the guarantee.

Wall-clock times rather than an interval because the previous scheme -
`horizon / generations` counted from process start - meant two instances started
an hour apart forgot different things at different moments, and "when does this
system forget" had no answer without knowing the deployment time.

`rotate-interval` overrides the session starts with a fixed spacing. It is
test-only: `scripts/perf/dedup-horizon-check.sh` sets it to compress the horizon
to a minute, because demonstrating the whole of it against a daily schedule
takes more than a day. The startup guard warns rather than refuses on this
schedule - enforcing it would stop the very script that proves the horizon - so
a production deployment left on `rotate-interval` is caught by a log line and
nothing else.

The order-id key space carries a guard on top of the horizon that does not
expire, because strategy child ids are derived from the parent - `deterministic(
parent + strategy + index)` - so a parent outliving the window regenerates ids
the window no longer holds. `DedupIndexLoader` therefore loads **every working
order regardless of age**, alongside the window. Without it the duplicate would
not even fail on the primary key: the writer upserts, so it would silently
overwrite a live order.

**Rotation is what bounds memory**, and it is the reason there is no periodic
reload. A Bloom filter cannot delete, so a single filter on a process that never
restarts fills until every answer is a false positive - roughly 0.1% at eight
hours, 15% at three days, 50% at a week. Nothing breaks and no short benchmark
can see it; the hot path just drifts back to Postgres. A live filter that has
been rotated out *is* the history for the period it covered, so rotation needs
no database read at all. `sessions-retained` filters are kept behind the live
one, and rotation runs at each session boundary.

**Sizing the two Caffeine tiers is now a measurement, not an argument.** Both
had `recordStats()` on since they were written and nothing read it, so how often
either answered was invisible. They are bound to Micrometer now:

```
cache_gets_total{cache="processed-commands",result="hit"|"miss"}
cache_gets_total{cache="trading-orders",result="hit"|"miss"}
cache_evictions_total{cache="..."}   cache_size{cache="..."}
```

The two tiers are not interchangeable with the index. `trading-orders` returns
the order object that modify, cancel and fill mutate, which a Bloom filter
cannot do - it answers a boolean. `processed-commands` returns the recorded
result so a retry gets its original 201, and that one is worth questioning:
50,000 entries at ~893 bytes of payload each (measured over 86,032 rows) is
around 55 MB, more than the whole deduplication index at 43 MB, to save a
database read on a path only retries take. It also covers by count rather than
by time - 50,000 entries is about seven minutes of traffic at 120 orders/sec,
against a client retry window measured in seconds.

**A benchmark cannot settle it**, and running one would look like it had: every
generated command carries a fresh id, so the hit rate is zero by construction -
the same zero already measured on the read-through timers. The signals to read
from real traffic are the hit rate and `cache_evictions_total`: evictions
staying at zero means the cache never fills and `maximumSize` is not doing
anything, while a high eviction count means entries leave before the retries
they exist for arrive.

**Warm-up costs latency, not correctness**, and it is measured. The load runs
after `ApplicationReadyEvent` on its own thread, so orders are accepted from the
first moment and answered by Postgres - the behaviour that predates the index -
until it finishes. Measured on a restart over a 20,001-row table:

```
Loaded 20001 processed commands, 20001 recent orders and 0 orders in
working trees into the deduplication index in 292 ms (window=PT24H)

Deduplication index ready: 40002 identifiers over PT24H, 43876 KB of filters
```

That is **~7.3 µs per identifier**, so the default sizing of 20M works out at
roughly **two and a half minutes** of warm-up, not seconds. The 42.8 MB is the
history filter sized for the whole horizon plus one live generation; the
retained generations replace the history rather than adding to it.

First run of the rotating design, both rates for 120 s through the gateway:

| | 100/s | 120/s |
|---|---:|---:|
| submit p50/p95/p99 | 7 / 10 / 19 ms | 6 / 15 / 34 ms |
| ring queue wait, mean | 0.027 ms | 0.114 ms |
| lookups answered from the index | 24,068 | 52,870 |
| lookups that reached Postgres | **0** | **0** |

Zero read-throughs across 26,401 orders with 20,001 historical rows already in
the table. What this run does **not** cover: rotation never fired, because the
interval is six hours and the run was four minutes.

**Two hours under continuous duplicates**, which is the evidence the
`enabled` flag was waiting for and on which it was removed. The index is now
unconditional; turning it off is a deployment. 10 orders/sec for 7,200 s with
one request in ten replaying an earlier `Idempotency-Key`:

| | |
|---|---:|
| orders submitted | 72,001 |
| **duplicate commands sent** | **~7,160** |
| `duplicate_reached_db` | **0** |
| `duplicate_order_reached_db` | **0** |
| `writer.rejected_rows` | **0** |
| lookups answered from the index | 131,836 |
| lookups that reached PostgreSQL | 10 (0.0076%) |
| business rejections / infra failures | 0.00% / 0.00% |

The duplicates are what make this mean anything. Every earlier run of this
system sent none, so both counters read zero for want of anything to catch;
`DUPLICATE_RATE` on `order-load.js` exists for that reason and defaults to 0 so
ordinary benchmarks are unchanged.

The ten PostgreSQL lookups are the designed path, not a fault: a replay whose
recorded result had been evicted from the Caffeine tier fell through to the
filters, which answered "possibly seen", and the database returned the original
result. Three tiers doing their own jobs.

**Two things this run is not evidence for.** Latency: sells are refused by the
venue for want of a share position, so half the orders rest and the book grows
to ~36,000, and venue latency decays as it fills by design. And the retry
pattern is synthetic - it replays keys from the last 50 that VU used, so it
never asks for anything old. A client retrying after a 30-second timeout would
fall through to PostgreSQL more often than 0.0076%.

**Two things must hold, or deduplication is wrong rather than slow:**

- *Exactly one instance accepts orders.* The order path asks `isPrimary()` in
  `DisruptorOrderPipeline` rather than assuming it - but nothing in this
  repository can answer that question across machines. See the next section.
- *Every processed identifier reaches the filter.* The risk lives in the write
  path, not in the filter, which has no false negatives of its own.

**Two oracles, one per key space**, and both must stay at zero:

| counter | table | what it proves |
|---|---|---|
| `emporia.oms.dedup.duplicate_reached_db` | `processed_order_command` | a command was processed twice |
| `emporia.oms.dedup.duplicate_order_reached_db` | `trading_order` | an id reported as new already existed |

The second one needed a change to earn its keep. `trading_order` is upserted on
every state change, so a conflict there is the expected case and carries no
information - which is why an order id slipping through was invisible. It would
not even fail on the primary key: the upsert would reset the existing row's
status, traded quantity and average price while leaving its identity columns
alone. Only an order's **first** write can prove anything, so `create` enqueues
through `AsyncDbWriter.enqueueNew`, which uses `ON CONFLICT DO NOTHING` and
reports the absorbed row. That also protects the existing row instead of
overwriting it.

Both rest on one behaviour that had never been checked: that a row absorbed by
`ON CONFLICT DO NOTHING` comes back as zero affected rows through a JDBC batch.
A counter reading zero proves only that it does not fire wrongly - the branch
that fires had never run. `AbsorbedConflictReportingSpec` now checks it against
a real PostgreSQL under the `postgres-it` profile, and
`scripts/perf/dedup-horizon-check.sh` has since made
`duplicate_reached_db` move off zero in a running service.

**The horizon, demonstrated rather than argued.** That script replaces the two
daily resets with `rotate-interval`, compressing the horizon to a minute, and
shrinks the Caffeine tier to ten entries, then sends one Idempotency-Key three
times:

| | result |
|---|---|
| first submit | order A |
| replay inside the horizon, cache evicted | **order A** - the filters said "possibly seen" and Postgres returned the recorded result |
| replay past the horizon, after 6 rotations | **order B**, and `duplicate_reached_db` 0 → 1 |

The middle row is the guarantee: deduplication holds inside the horizon even
when the cache cannot help, so the filter-then-database path is doing the work.
The last row is the bound, behaving exactly as documented - and the only time
the duplicate oracle has fired in a running system.

## The portfolio outbox distinguishes settled changes from margin reservations

- **Where**: `exchange-core` 0.5.8's `EmporiaPortfolioChange`, and
  `change_kind` on `exchange_core_portfolio_outbox`

Publishing a snapshot for every accepted command filled the outbox at order-flow
rate while it could only drain at delivery rate. Measured at 120 orders/sec for
ten minutes: **72,002 rows enqueued, 248 delivered**, with the claim query's
per-client anti-join running 71,753 subquery loops at 2,271 ms a call and
holding a Postgres core at 102% indefinitely.

`SETTLED` (a fill, or a funding adjustment) is delivered and acknowledged
individually and is **never** collapsed - audit requires one confirmed delivery
per completed change. `RESERVED` (margin moving on an order that has not traded)
supersedes this client's earlier undelivered reservation, since a snapshot
carries the whole balance and only the newest one carries anything. Enqueue is
single-threaded so insertion order matches production order; superseding
compares `sequence_id`.

After: **0 pending, 100% resolved, Postgres 0.88%**.

## The risk seed reads settled_balance, never available_balance

- **Where**: `portfolio_balance.settled_balance`, read by
  `/internal/v1/portfolios/{clientId}/risk-seed`

Onboarding a client into the matching engine seeds it from portfolio-service,
and the engine starts with an empty book and therefore no margin holds. Seeding
from a hold-adjusted balance lost that margin permanently, silently, on every
venue reset. Observed locally: a client seeded at 999,999,999,999 read back
999,291,109,999 against 72,002 resting orders.

`available_balance` follows every snapshot so a trader sees holds as they
happen; `settled_balance` follows only settled changes and is what the seed
reads.

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
