# No blocking database call on the order hot path

**The rule.** Nothing on the single writer thread may block on PostgreSQL. Not a
read, not a write, not a lock.

This document records why that rule exists, the five mechanisms that implement
it, the three constraints it imposes in exchange, and the evidence for each.
Measurements live in `CONFIGURATION.md`; this is the shape they support.

For an orientation to the system as a whole, read `ARCHITECTURE_FLOW.md` first.
This document assumes it.

## Why: the database was most of the writer's work

`OrderStateCache` has two lookups on the order path - `existsById` for the
duplicate-order guard, and `findProcessedById` for idempotency. Both read
through to PostgreSQL.

They were not "mostly misses". They were **zero cache hits in 14,402 orders**,
because a `CREATE` carries freshly generated identifiers, so the read-through
fires every single time. Together they cost **2.312 ms of the handler's
2.405 ms**.

The cost is on the single writer thread, and that is what makes it structural
rather than merely slow. A fixed per-event cost there does not add to latency,
it **multiplies**: every command pays it and every command queued behind it
waits. The same effect was measured independently with tracing overhead - 0.6 ms
per command at 120 orders/sec surfaced as several hundred milliseconds of ring
queue wait.

Removing the two reads, six alternating runs at 120 orders/sec:

| | ring queue wait, mean | max | k6 submit p99 |
|---|---:|---:|---:|
| index on | 0.035 - 0.230 ms | 17 - 52 ms | 27 - 73 ms |
| index off | 1.872 - 5.712 ms | 248 - 479 ms | 106 - 361 ms |

## How: five mechanisms

**1. One writer thread, an LMAX Disruptor ring.** All order commands are applied
in sequence by a single thread, so state transitions need no locks and no
database-level serialisation. This is what makes the rule enforceable at all -
there is exactly one place to keep clean.

**2. `RotatingDedupIndex` answers "certainly never seen" from memory.** A Bloom
filter set, rotated at each trading-session start. It replaces both hot-path
reads. It cannot produce a false negative from the filter itself, so "never
seen" is trustworthy; "possibly seen" falls through to the database, which then
gives the exact answer. Because a Bloom filter cannot delete, generations are
retired on a schedule - and the span they cover is a **correctness bound**, not
a cache-sizing one. See `CONFIGURATION.md`.

**3. Two Caffeine tiers, which are not interchangeable with the index.**
`trading-orders` returns the order object that modify, cancel and fill mutate -
a Bloom filter cannot, it answers a boolean. `processed-commands` returns the
recorded result so a retry gets its original 201.

**4. A memory-mapped write-ahead log covers the acknowledged-but-unwritten
window.** `append` writes into a mapped region and does **not** `force`. It
costs 0.011 ms of the handler's 3.047 ms - 0.3%.

**5. `AsyncDbWriter` batches the writes off the hot path.** The order is
answered 201 once the ring has applied it; the row reaches PostgreSQL on a
flush, by default every 10 ms.

Worth stating because it inverts the usual LMAX advice: **the WAL is not the
cost here.** Moving journalling to a parallel handler, the standard
recommendation, would buy 0.3%. The database reads were the cost.

## What actually moved into memory

Precisely one answer: **"this identifier has certainly never been seen."**

Everything else - every positive answer, every order object, every recorded
result - still comes from PostgreSQL. PostgreSQL is the record; memory is what
the system acts on and, for that single answer, what it trusts.

This is a narrower claim than "the database is a cache" and the narrowness is
the point: it is the only answer for which being wrong in the safe direction
costs nothing (one database lookup) and being wrong in the unsafe direction is
impossible from the filter itself.

## The price: three constraints

These are not caveats. They are the terms of the trade, and each is enforced or
instrumented rather than assumed.

**1. Exactly one instance may accept orders.** Two instances would each believe
the same identifiers are new. `DisruptorOrderPipeline` refuses orders with 503
when `isPrimary()` is false. The limit is real and documented: no leader-election
provider in this repository excludes a second *machine*.

**2. A lost durable write is silent.** The hot path no longer reads from
PostgreSQL, so nothing on it would notice an empty database. This is the direct
consequence of the rule, and the reason the counters below exist.

**3. The WAL recovers process death, not machine loss.** Mapped pages belong to
the operating system, so they survive `kill -9` and a JVM crash. They do not
survive the machine dying. At 120 orders/sec with a 10 ms flush cadence the
exposure is one or two orders that have already been answered 201 and may
already exist at the venue - and **nothing notices**.

The remedy for the third is known and deliberately not taken: append on the
writer thread, have a separate thread `force`, and only then complete the HTTP
response - group commit with a durable acknowledgement. It never loses an
acknowledged order and adds the force interval to every submit. That is exactly
the blocking call this rule exists to avoid. Revisit if the requirement becomes
"an acknowledged order survives machine loss".

## The oracles

Since the hot path cannot notice a database problem, three counters exist to let
the database speak from the far side of it. **All three must stay at zero.**

| counter | fires when |
|---|---|
| `emporia.oms.dedup.duplicate_reached_db` | a duplicate command reached the writer |
| `emporia.oms.dedup.duplicate_order_reached_db` | a duplicate order id reached the writer |
| `emporia.oms.writer.rejected_rows` | the database refused a row |

They are database-side by design. An in-memory check that the in-memory index is
working would be arguing with itself.

## Evidence, and what it does not cover

- **Two hours, 10 orders/sec, one request in ten replaying an earlier
  `Idempotency-Key`**: 72,001 orders, ~7,160 real duplicates, all three counters
  at 0, and 10 of 131,836 lookups reaching PostgreSQL - each of those the
  designed fall-through, not a fault.
- **Crash**: `scripts/perf/crash-recovery-check.sh` and
  `scripts/perf/wal-recovery-check.sh` kill the JVM mid-burst and assert both
  that unflushed orders are replayed and that a command in flight at the kill is
  still deduplicated afterwards.
- **Horizon**: `scripts/perf/dedup-horizon-check.sh` compresses the horizon and
  demonstrates a repeated key deduplicating inside it and not past it. It is the
  only thing that has moved `duplicate_reached_db` off zero outside a unit test.
- **Warm-up costs latency, not correctness**: orders are accepted from the first
  moment and answered by PostgreSQL until the load finishes. Over a 20,001-row
  table that was 292 ms.

**What it is not evidence for.** The soak ran on a development machine, once, at
a tenth of the benchmarked rate, with a synthetic retry pattern that only ever
replays recent keys. It says nothing about latency - and nothing about machine
loss, which no test here exercises.

## Where to look

| concern | code |
|---|---|
| single writer, primary check | `DisruptorOrderPipeline` |
| memory-authoritative "never seen" | `RotatingDedupIndex`, `CommandDedupIndex` |
| when filters rotate | `RotationSchedule`, `DedupIndexRotation` |
| three-tier lookup | `OrderStateCache` |
| batched writes, conflict absorption | `AsyncDbWriter` |
| crash window | `MemoryMappedWalLogger` |
| startup load | `DedupIndexWarmup`, `DedupIndexLoader` |
