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

**Order reconciliation exists, in one direction.** The `reconciliation` actuator
endpoint walks every order this service holds as LIVE or PARTIALLY_FILLED and
asks the venue whether it knows of it, so drift surfaces by inspection instead
of as an "unknown lifecycle order" on the next command. Its javadoc explains why
it does not walk the other way: enumerating every symbol's resting book is not
something the venue's per-client report API offers cheaply, and "no code path in
this system creates a venue order without first writing an order-management
row".

**That argument holds for code paths and not for durability failures**, which is
exactly the gap above. `create` does write its row before dispatching - but the
write is asynchronous, and if it never lands (the machine dies inside the 10 ms
window, or a row the database refuses blocks the queue) the venue holds an order
this service has no record of. Finding that needs the venue-to-service
direction, and nothing walks it.

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
