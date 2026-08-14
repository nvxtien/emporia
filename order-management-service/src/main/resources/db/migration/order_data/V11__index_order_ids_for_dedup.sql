-- Supports loading order ids into the in-memory deduplication index at startup.
--
-- The index answers two questions, not one: whether a commandId was processed
-- (idempotency) and whether an orderId already exists (the 409 guard in
-- OrderCommandHandler.create). Only the first was ever loaded, so after a
-- restart every pre-existing orderId read as "never seen" and the guard was off
-- until this process happened to create that order itself.
--
-- Two arms, because the two risks have different shapes.
--
-- created_at covers the recent window, matching the deduplication horizon.
--
-- order_status covers every order still working, regardless of age, and that
-- arm is the one that closes the hole rather than narrowing it. Strategy child
-- slices carry deterministic ids - deterministic(parent + strategy + index) -
-- so a parent that outlives the horizon regenerates ids the window no longer
-- holds. Accepting one would not fail on the primary key: the writer upserts,
-- so it would silently overwrite a live order. The working set is small, which
-- is why age can be ignored on this arm.
CREATE INDEX IF NOT EXISTS idx_trading_order_created_at
    ON trading_order (created_at DESC);

--
-- Composite rather than a partial index on order_status alone: the column has a
-- handful of distinct values, and carrying id makes the load an index-only scan
-- instead of a heap fetch per working order. A partial index would be smaller
-- still, but H2 backs the migration test and has no such syntax, and a schema
-- that only runs on one engine is not worth the bytes saved.
CREATE INDEX IF NOT EXISTS idx_trading_order_working
    ON trading_order (order_status, id);
