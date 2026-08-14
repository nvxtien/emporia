# Plan: no blocking database read on the order hot path

Status: awaiting approval. No code written yet.

## The measurement this rests on

One 120 orders/sec run, 120 s, 14,402 orders, full path through the gateway,
`full-equity-risk`, `blocking` wait strategy on both rings. Timers recorded
inside `OrderStateCache` itself, so they attribute by call site rather than by
JDBC pool:

```
                       calls   total(s)   ms/order
existsById             14402     14.386      0.999
findProcessedById      14402     18.908      1.313
──────────────────────────────────────────────────
two lookups                                  2.312
handler total                                2.405
everything else                              0.093
                                    share:  96.1%
```

```
source="cache"  →      0 calls
source="db"     → 14,402 calls
```

Not "mostly misses" — **zero hits in 14,402 orders**. A `CREATE` carries a
freshly generated `commandId` and `orderId`, so the read-through cache can never
answer, and the fallback fires every time. The cache optimises the rare duplicate
and does nothing for the common new order.

For scale, the things already optimised this session: `json()` 0.018 ms/order,
dispatch to shard 0.007, async persist enqueue 0.041.

**What is not established:** how much p99 improves. This run measured p99 72 ms;
earlier runs at the same rate and config measured 254 ms. Run-to-run variance in
this environment has been up to 21x. The 2.312 ms is a stable measurement over
14,402 samples; any latency claim needs repeated runs after the change.

## Why the queue forms at 29% utilisation

120/s × 2.405 ms = 28.9% utilisation. Simple queueing theory predicts almost no
queue. Kingman's formula depends on the **variance** of service time, not just
the mean: a Postgres query averaging 0.6 ms has a long tail — pool wait,
checkpoint, lock — and one 200 ms outlier puts 24 orders behind it. This is the
only mechanism proposed this session that explains the observed `depth=86,
idle=0`, which GC, Java locks and thread parking each failed to explain.

Removing out-of-process I/O from the writer thread removes the variance, which
matters more than removing the mean.

## Design

The in-memory structure becomes the **authority** for "has this command been
processed", not a cache in front of the database. A read-through cache can never
answer "never seen" — it only knows what it holds.

Three tiers:

| tier | answers | covers | size |
|---|---|---|---|
| exact map (50,000) | *what was the previous result* | client retry window, ~7 min at 120/s | as today |
| Bloom filter | *definitely never seen* | one trading session | ~6 MB at 120/s |
| database | the remainder | 0.1% false positives + very old retries | ~0.12 queries/sec |

A Bloom filter has **no false negatives**: "not present" is exact. That is the
answer the hot path needs, and it is the answer the current cache cannot give.
A false positive costs one database lookup which then returns the correct
answer, so semantics are preserved exactly.

`processed-max-size: 50000` was never the problem — 7 minutes comfortably covers
a client retry window. The problem was using it as the authority for "never
seen", which it cannot be. The Bloom filter takes that job.

### Bloom sizing

At a 0.1% false-positive rate, ~1.8 bytes per entry, for an 8-hour session:

| rate | entries | Bloom |
|---|---|---|
| 120/s | 3.5 M | 6 MB |
| 500/s | 14.4 M | 26 MB |
| 1,000/s | 28.8 M | 52 MB |

Session rotation: a Bloom filter cannot delete entries. At session end open a new
filter and keep querying the previous one for an overlap period. This is the part
most likely to be got wrong and needs its own tests.

### Startup: accept orders immediately, with no dedup gap

While loading, the Bloom filter is incomplete, so it would report "never seen"
for something it has seen — a false negative, the one direction that lets a
duplicate through. So during load the Bloom filter is not trusted:

```
boot  →  mode = WARMING  →  accept orders, check via DB (2.3 ms, today's behaviour)
              │
              ├─ background: bulk-load the session's commandIds from Postgres
              ├─ background: replay the WAL (covers what AsyncDbWriter had not flushed)
              └─ concurrently: every command processed since boot also writes to the index
              ↓
        mode = READY     →  memory only, ~1 µs
```

At the flip the index holds *(history from DB)* ∪ *(WAL)* ∪ *(everything
processed since boot)*. No gap.

The WAL leg is not optional: database writes are asynchronous, so on a crash
there are accepted commands not yet in Postgres. Loading from the database alone
leaves exactly that window open, and the WAL exists for exactly that window.

Degradation during warm-up is in performance, not correctness — the first
seconds run at today's latency.

### Drop `existsById` entirely

For `CREATE`, `orderId` is generated server-side by `UUID.randomUUID()`, so it is
never a duplicate in practice. Its one narrow role — replay after a crash that
persisted `trading_order` but not `processed_order_command` — is already covered
by the `commandId` check, with the `trading_order` primary key as the backstop.

Removes 0.999 ms/order, 43% of the cost.

### Protecting the index write path

A false negative — the index reporting "never seen" for something processed — is
the only failure mode that produces a duplicate order. The risk lives here, not
in the Bloom filter's mathematics.

**Where an entry can be lost**

`putProcessed()` is called from four separate places in `OrderCommandHandler`
(lines 103, 206, 245, 258). Four places to forget. Add a command type, miss one,
and the result is a silent false negative with nothing reporting it. Also:
writing to the index *after* the command is processed (crash in between);
treating the Bloom filter as a spill-over for entries evicted from the exact map
(one missed eviction is a permanent false negative); rotating the filter at
session end without an overlap; and reading the index from a different thread
than the one that writes it.

**Prevention is structural, not disciplinary**

1. **One insert site, not four.** Move the write out of the branches and wrap
   `handle()`: whatever branch returns a `ProcessedCommand`, that result enters
   the index. A new command type then *cannot* forget, because there is nowhere
   left to forget.
2. **Both tiers written together, always.** The Bloom filter is not where evicted
   map entries land. Every processed command enters both at the same moment.
3. **The fast check stays on the writer thread.** This reverses the earlier
   instinct to move it before the ring, and the reason is correctness rather than
   performance. A Bloom filter is a `long[]`; reading it from another thread has
   no happens-before guarantee. Writing and reading on the same thread removes
   that entire class of bug, and the check now costs ~1 µs, so there is nothing
   to gain by moving it. The single-writer property becomes an asset rather than
   a constraint.

**Detection: the schema already provides a free, always-on oracle**

```sql
CREATE TABLE processed_order_command (
    command_id UUID PRIMARY KEY,   -- this
```

If the index ever returns a false negative, the duplicate is processed and
`AsyncDbWriter` then attempts to insert a `command_id` that already exists — a
primary key violation. Postgres is already checking this invariant continuously,
for every order, at no cost. The system simply is not listening.

So: count primary key violations on `processed_order_command`, expose the count
as a metric, and alert on anything above zero. That count *is* the number of
false negatives that have occurred.

For a system whose failure mode is a duplicate order, prevention alone is not
enough — something has to report when prevention fails. This makes the invariant
continuously verified in production rather than only argued in a design
document.

Requires checking how `AsyncDbWriter` currently handles that failure; most likely
it logs and moves on.

### The rare slow path

0.1% of orders (Bloom false positive, or a retry older than the exact window)
still need a database lookup. 0.1% × 1.3 ms is 0.0013 ms on average — but this
session's central lesson is that the tail comes from variance, not the mean. One
order in a thousand blocking on the writer thread still generates a tail.

That branch goes to a small dedicated executor, so it never runs on the writer
thread. This is a contingency mechanism for a rare branch, not the path every
order takes — which keeps it far cheaper than routing all traffic through an
executor, and shrinks the ordering question (below) to "what happens to an order
that takes the slow branch" rather than a system-wide invariant.

### Ordering hazard

Three call sites submit to the ring:

| call site | thread | how it waits |
|---|---|---|
| `OrderCommandController:190` | Tomcat | `.join()`, blocking |
| `AeronOrderCommandSubscriber:112` | single poller | fire-and-forget |
| `ExecutionEventConsumer:633` | dispatcher shard | SMART/VWAP child slices |

If the check becomes asynchronous, a `CANCEL` taking the fast branch can reach
the ring ahead of a `CREATE` for the same order taking the slow branch. This is
the same bug class the Kafka partition key guarded against, and the class
`remember()`/`giveUp()` already had to be fixed for once.

Because the fast branch is synchronous and in-memory, only the rare slow branch
can reorder. The plan is to make the slow branch preserve submission order per
`orderId` rather than to make every order asynchronous. This needs an explicit
test, not a design argument.

## Single-instance authority: from a performance assumption to a correctness one

`OrderStateCache`'s javadoc states the basis:

> *"the Kafka consumer group assigns each partition to exactly one OMS instance.
> Command handlers for a given order always run on the same instance, so the
> cache is authoritative for write-path reads."*

That basis no longer exists. Verified:

```
@KafkaListener in order-management-service:  0
isPrimary() on the order path:               0 call sites
                                             (only HotStandbyJournalReplayer:46)
```

Order intake moved in-process at commit `67eaecf`; orders arrive over HTTP.
`LeaderElectionService` runs and does elect a primary, but nothing on the order
path asks it. Two instances would both accept orders today.

The change escalates what that costs:

| | a second instance causes |
|---|---|
| today (miss → DB) | slower; still **correct**, the database is the authority |
| after (no DB fallback) | **incorrect** — instance B knows nothing of what A processed, accepts the duplicate, opens a duplicate position |

An implicit assumption whose failure mode is latency can be lived with. One whose
failure mode is a duplicate order cannot.

Documentation alone is not enough — the javadoc above *was* the documentation, and
it went stale unnoticed when Kafka was removed. So this is enforced on the order
path:

```
pipeline.submit()  →  if (!leaderElection.isPrimary()) → 503
```

A volatile read, free against the 2.3 ms being removed, and it turns silent
split-brain into a visible failure.

**Remaining limit, stated rather than hidden:** the current provider is
`local-filelock`, which excludes a second process on one machine and does *not*
exclude a second machine. After this change the system's honest claim is:

> Command deduplication is correct if and only if exactly one instance accepts
> orders. That is enforced within a machine and is **not** enforced across
> machines.

This makes the HA gap — deferred earlier in this session — a prerequisite rather
than deferrable debt.

## Stale documentation to delete

Six javadoc comments cite a Kafka-based architecture that no longer exists:

- `OrderStateCache.java:37` — the single-instance-authority basis (the load-bearing one)
- `OrderCommandController.java:54` — "published to Kafka asynchronously out-of-band"
- `FixExecutionVenueGateway.java:402` — "order commands arrive on a Kafka consumer"
- `ExchangeCoreExecutionVenueGateway.java:526` — "by then the Kafka listeners are consuming"
- `ExecutionEventConsumer.java:684` — "the Kafka listener runs on"
- `ExchangeCoreLifecycleRebuilder.java:177` — "a post-crash Kafka"

Keep `ShardedOrderDispatcher.java:18,69` — those describe Kafka as the thing
*replaced*, which is accurate and worth keeping.

`OrderCommandController.java:52` ("eliminating Kafka round-trips") is accurate as
history; reword only if it reads as present tense.

## Phases

Each phase is its own commit, independently buildable and verifiable.

**Phase 0 — enforce single-instance authority.** `isPrimary()` check on the order
path returning 503; test that a non-primary refuses orders. Ships *before* the
index change, so the invariant the index depends on exists first.

**Phase 1 — delete `existsById` from the CREATE path.** Smallest independent win,
0.999 ms/order, no new machinery. Measure before continuing; if 43% is enough,
the rest may not be worth its complexity.

**Phase 1b — the false-negative oracle, before anything depends on it.** Turn the
primary key violation on `processed_order_command` into a metric with an alert.
This ships before the index exists, so that when the index does arrive there is
already something watching it — and so the metric's baseline of zero is
established against today's known-correct behaviour.

**Phase 1c — collapse the four `putProcessed()` call sites into one.** Pure
refactor, no behaviour change, verifiable on its own. Doing it before the index
means the index has exactly one write path to protect from the start.

**Phase 2 — the index.** Bloom + exact map + WARMING/READY modes + bulk load +
WAL replay, both tiers written together, check and insert both on the writer
thread. Behind a flag, defaulting to today's behaviour.

**Phase 3 — the rare slow branch onto its own executor**, with the per-`orderId`
ordering test.

**Phase 4 — flip the default, measure repeatedly.** Not one run.

**Phase 5 — session rotation of the Bloom filter**, with tests.

**Phase 6 — documentation.** Delete the six stale comments, rewrite
`OrderStateCache`'s javadoc to state the real invariant, record the constraint in
`CONFIGURATION.md`.

## Risks

- **False negatives are the only failure mode that matters.** A Bloom filter has
  none *provided every processed command was inserted*. The risk lives in the
  write path into the index, not in the filter — see "Protecting the index write
  path" above for the prevention and the detection oracle. Tests concentrate
  there, not on performance.
- **Bloom rotation at session boundaries** — the part most likely to be got wrong.
- **Multi-machine deployment silently breaks correctness.** See above.
- **The latency benefit is unquantified.** Only the 2.312 ms cost is measured.
  Phase 4 must run repeatedly, alternating order, resetting the book, before any
  latency claim is published — the same discipline the wait-strategy A/B needed
  after a conclusion had to be withdrawn.

## Tests

Three, aimed at the risk rather than at the speedup. None of them measures
performance, deliberately.

1. **Property test** (jqwik is already a dependency): for any sequence of
   commands, every processed `commandId` is present in the index. This is an
   invariant, which suits a property test better than examples.
2. **Crash test**: `kill -9` mid-run, restart, resubmit a command that was
   in flight, assert it is deduplicated. Extends the existing
   `crash-recovery-check.sh`.
3. **Rotation test**: cross a session boundary, then retry a command from before
   it.

Plus the per-`orderId` ordering test for the slow branch (Phase 3).

## Still to verify before implementation

- `OrderCommandReplayHarness` calls `handler.handle()` directly; confirm what it
  needs from the index and whether it must load before replaying.
- How `AsyncDbWriter` currently handles a primary key violation on
  `processed_order_command` — this is the false-negative oracle and it needs to
  become a metric with an alert, not a swallowed log line.
- `processed_order_command` (V1__create_order_store.sql:69) has
  `processed_at TIMESTAMP WITH TIME ZONE NOT NULL` but **no index on it**.
  Loading the session by `processed_at` would scan the table, and clock skew
  would make the load silently incomplete. Load by a monotonic key instead and
  reconcile the row count rather than assuming it is complete. An index is needed
  either way.
- `AeronOrderCommandSubscriber` is fire-and-forget; confirm it needs no change
  once the fast branch is synchronous.
