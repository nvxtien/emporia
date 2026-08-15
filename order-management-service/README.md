# Order management service

The order-management service owns persisted order state, immutable order events,
execution records, and command-idempotency results.

## Responsibility and integration

The service runs on port `8086`. Browser mutations
(`POST`/`PUT`/`DELETE /api/orders/**`) are handled directly here: they land on
a single-writer LMAX Disruptor ring buffer, apply the order lifecycle rules in
a database transaction, and the correlated result is returned to the waiting
HTTP request. Read requests are also routed directly here:

```text
GET /api/orders
GET /api/orders/stream
GET /api/orders/{orderId}
GET /api/orders/{orderId}/history
GET /api/orders/{orderId}/executions
```

PostgreSQL schema `emporia_order_data` contains `trading_order`, `order_event`,
`execution`, and `processed_order_command`. The schema name remains unchanged
from the former service identity so existing orders remain available.

All reads and mutations are desk-scoped using the signed JWT `desk` claim.
`GET /api/orders/stream` sends the current desk projection followed by
continuous SSE updates, published directly by `ShardedOrderDispatcher`'s shard
workers as each order domain event is processed. Orders retain their creating
user as `ownerSubject`, so users on one desk see shared flow without losing
attribution.

Execution routing (DMA, SMART, VWAP algorithmic strategies, and venue
gateways) runs in-process inside this same service rather than as a separate
deployable - see [Order command flow](../README.md#order-command-flow) in the
repository root README and the
[DMA, SMART, and VWAP execution guide](../docs/execution/README.md). Fills,
venue rejects, and venue cancellations apply directly in-memory via
`ExecutionCommandHandler`. Execution references are unique, fills use the same
aggregate invariants as user commands, and each accepted transition produces an
immutable order event. A child fill and all ancestor roll-ups are saved in one
transaction, so a partial strategy fill is immediately visible on its parent.

The authenticated internal endpoints under `/internal/execution/**` expose
active direct orders and SMART/VWAP parent/child state. The execution side of
this same service still reaches them over HTTP (via `TradingDataClient`, using
its own OAuth2 client-credentials token) rather than an in-process call - that
boundary predates the OMS/execution merge and has not been collapsed. They are
the durable source for restart recovery and are not routed to browsers.

## Order invariants

Order invariants are enforced at two boundaries.

### Java aggregate validation

`TradingOrder` validates its state:

- after construction;
- before and after modification, fill application, or cancellation;
- through JPA `@PrePersist` and `@PreUpdate` callbacks.

The aggregate requires:

- positive quantity aligned to the listing size increment;
- positive tick size and size increment;
- a null price for market orders;
- a positive, tick-aligned price for limit orders;
- non-negative traded and remaining quantities;
- `traded + remaining = quantity`;
- a positive average trade price when one is present;
- positive, increment-aligned fills that cannot exceed remaining quantity;
- weighted-average trade-price accounting across multiple fills;
- status-specific quantity accounting;
- no modification or second cancellation after a terminal status;
- pending cancellation is represented by an active `status` with
  `target_status=CANCELLED`;
- venue fills may win the cancel race, including after a cancellation
  acknowledgement, while preserving exact quantity accounting.

The command handler performs equivalent request validation and returns HTTP-style
`400` or `409` outcomes before mutating the aggregate. A modification must leave
quantity remaining: its new total quantity must be strictly greater than the
quantity already traded.

### PostgreSQL constraints

Flyway migration
[`V2__enforce_order_invariants.sql`](src/main/resources/db/migration/V2__enforce_order_invariants.sql)
adds named `CHECK` constraints to `trading_order`. These protect the database
from invalid writes that bypass Java, including manual SQL and future consumers.

The database checks arithmetic, increment and tick alignment, price/type
consistency, allowed enum values, and status-specific quantity accounting.
Temporal transition rules—such as refusing to modify an already-cancelled
aggregate—remain in Java because a row-level `CHECK` constraint cannot compare
the old and new versions of a row.

Flyway validates existing rows while adding the constraints. Deployment stops
at migration `V2` if historical data violates an invariant; correct that data
before retrying rather than weakening the constraint.

Migration `V3` adds `desk_id`, backfills existing rows from `user_subject`, and
adds the desk/time index used by blotter reads and cancel-all.

Migration
[`V4__enforce_pending_cancel_invariants.sql`](src/main/resources/db/migration/V4__enforce_pending_cancel_invariants.sql)
allows only three target-state shapes: steady state (`target_status=status`),
an active order targeting `LIVE`, or an active order pending
`CANCELLED`. This prevents malformed pending-cancel rows written outside Java.

## Verify

Run all order-management tests:

```bash
mvn -f emporia/pom.xml -pl order-management-service -am test
```

Run only invariant-related tests:

```bash
mvn -f emporia/pom.xml -pl order-management-service -am \
  -Dtest=TradingOrderInvariantTest,OrderDatabaseConstraintTest,TradingOrderPropertyTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

`OrderDatabaseConstraintTest` runs all Flyway migrations and attempts invalid
direct SQL writes against an isolated PostgreSQL-compatible H2 database. The
jqwik suite generates numeric values, two-fill weighted averages, randomized
command sequences through the production handler, and randomized venue-event
sequences through the aggregate. It covers pending cancellation and both
arrival orders of the cancel/fill race, including late fills after a venue
acknowledgement.

The migrations were also verified against PostgreSQL 16: valid and pending
cancel rows were accepted, malformed target states and overfills were rejected,
and the cancel/fill/ack sequence retained valid accounting.

## PostgreSQL specifications

The optional `postgres-it` Maven profile starts PostgreSQL 16 with
Testcontainers and applies the real Flyway migrations, so Hibernate validates
its mappings against the production schema rather than against tables a test
created.

`TradingOrderPostgresPersistenceSpec` walks an order through a cancellation
request, a partial fill that lands before the venue hears about it, and the
confirmation - then checks the persisted state and invariants. It also asserts
that saving an order the database has never seen inserts it, which is a
regression guard: while `entity_version` carried `@Version`, Spring Data decided
new from existing by `version == null`, the constructor assigned 0, and every
save of a new order took the merge path and failed.

`AbsorbedConflictReportingSpec` checks the one behaviour both duplicate oracles
rest on - that a row absorbed by `ON CONFLICT DO NOTHING` comes back as zero
affected rows through a JDBC batch.

There is no optimistic-locking specification any more, because there is no
optimistic locking. `@Version` came off `TradingOrder`: every order state change
runs on the single Disruptor writer thread, so two transactions cannot race one
row, and the writes that would carry the guard leave through raw JDBC where
Hibernate is not involved. `entity_version` is now a revision counter advanced
by `TradingOrder.recordRevision`, which is what callers echo back as
`expectedVersion`. See `CONFIGURATION.md`.

With a Docker-compatible runtime running:

```bash
mvn -f emporia/pom.xml -Ppostgres-it -pl order-management-service -am test
```

When using OrbStack and Testcontainers cannot discover the active Docker
context, expose its socket explicitly:

```bash
DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock \
  mvn -f emporia/pom.xml -Ppostgres-it -pl order-management-service -am test
```

The specification's `*Spec` name keeps it out of Maven's default test selection,
so ordinary unit tests do not start Docker.

## Controlled concurrency pilot

`TradingOrderFraySpec` also lives in the standard `src/test/java` source set.
The optional `fray` Maven profile runs only that specification on Fray's
instrumented JVM. The pilot races a full execution against cancellation on the
same production `TradingOrder` instance. It checks that exactly one transition
wins, the other is rejected, the final state is either `FILLED` or `CANCELLED`,
and all accounting invariants still hold.

Run it from the repository root:

```bash
mvn -f emporia/pom.xml -Pfray -pl order-management-service -am test
```

The first profile run downloads Fray's dependencies and prepares its
instrumented JDK under the module's `target` directory. The specification's
`*Spec` name keeps it out of Maven's default test selection, so the regular
build has no Fray startup cost.

This test covers in-process Java interleavings only. Nothing at the database
decides races between separate service processes - optimistic locking is gone,
and deduplication is correct only while exactly one instance accepts orders,
which `DisruptorOrderPipeline`'s `isPrimary()` check enforces on the order path.
`ShardedOrderDispatcher`'s per-order-ID shard ordering, database scheduling, and
network timing still require integration tests.
