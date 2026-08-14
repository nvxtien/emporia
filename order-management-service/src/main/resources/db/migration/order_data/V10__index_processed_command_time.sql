-- Supports loading a trading session's processed commands into the in-memory
-- deduplication index at startup.
--
-- Without this the load scans the whole table, which grows without bound: it is
-- the idempotency record and is never pruned on the order path. The index makes
-- the load proportional to the session rather than to all history.
--
-- Loading by processed_at rather than by a monotonic key is sound here because
-- exactly one instance accepts orders, so every row in the window came from one
-- clock. That is enforced on the order path rather than assumed - see the
-- isPrimary() check in DisruptorOrderPipeline.
CREATE INDEX IF NOT EXISTS idx_processed_order_command_processed_at
    ON processed_order_command (processed_at DESC);
