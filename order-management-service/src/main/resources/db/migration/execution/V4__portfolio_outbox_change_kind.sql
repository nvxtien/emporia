-- Separates the two kinds of balance change the outbox carries, so the one
-- that may be collapsed can be, and the one that may not never is.
--
-- Why: every accepted order published a snapshot, settled or not, so the queue
-- grew with order flow while it could only drain with delivery. Measured at 120
-- orders/sec: 72,002 rows enqueued, 248 delivered, and the claim query's
-- per-client anti-join reached 2.3 s once ~72k rows were pending.
--
-- SETTLED covers fills and funding adjustments: each is an audit record and is
-- delivered and acknowledged on its own. RESERVED covers margin moving on an
-- order that has not traded: only the newest one per client carries anything,
-- so an undelivered older one is marked SUPERSEDED instead of being sent.
--
-- Defaults to SETTLED so rows written before this migration are never
-- collapsed - the conservative direction for anything already queued.
ALTER TABLE exchange_core_portfolio_outbox
    ADD COLUMN change_kind VARCHAR(10) NOT NULL DEFAULT 'SETTLED'
        CHECK (change_kind IN ('SETTLED', 'RESERVED'));

ALTER TABLE exchange_core_portfolio_outbox
    DROP CONSTRAINT IF EXISTS exchange_core_portfolio_outbox_status_check;

ALTER TABLE exchange_core_portfolio_outbox
    ADD CONSTRAINT exchange_core_portfolio_outbox_status_check
        CHECK (status IN (
            'PENDING',
            'IN_FLIGHT',
            'RETRY',
            'PUBLISHED',
            'SUPERSEDED',
            'DEAD'
        ));

-- Supports the supersede statement on enqueue, which looks up this client's
-- pending reservations by sequence. Partial so it only ever spans the pending
-- set, which this change keeps to roughly one row per client rather than the
-- whole table.
CREATE INDEX idx_exchange_core_portfolio_outbox_pending_reserved
    ON exchange_core_portfolio_outbox (client_id, sequence_id)
    WHERE status = 'PENDING' AND change_kind = 'RESERVED';
