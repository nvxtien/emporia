# LMAX Phase 4 Progress

## Durable PostgreSQL output handoff

- OMS BLP emits typed `OrderDomainEvent` values into `AsyncDbWriter`.
- Flyway migration `V14__create_order_delivery_outbox.sql` adds the durable
  `order_delivery_outbox` table.
- `DurableOrderOutputDispatcher` claims rows with `FOR UPDATE SKIP LOCKED`,
  uses a lease, preserves per-order ordering, retries failures, and marks
  rows delivered only after dispatch completes.
- Output events are persisted in the same writer transaction as the related
  OMS state/event writes.
- Metrics cover delivered events, retries, pending depth, oldest pending age,
  and delivery lag. Delivered rows are retained for seven days by default.

## Verification

- Targeted order-management tests pass with zero failures and zero errors.
- PostgreSQL integration profile passes with Testcontainers, including the
  expired-lease recovery drill for a fresh dispatcher.
- `git diff --check` passes.

## Remaining work

- The PostgreSQL crash/retry drill is now covered by
  `DurableOrderOutputPostgresSpec`.
- Define bounded dispatcher backpressure and complete Phase 4 checkpoint.
- Preserve the documented process-death versus machine-loss durability
  distinction unless the product requirement changes.
