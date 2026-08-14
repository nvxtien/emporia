-- Splits what the risk seed reads from what a live view reads.
--
-- available_balance follows every snapshot, settled or not, so a trader sees
-- margin holds as they happen. settled_balance follows only settled changes -
-- fills and funding - so it never includes a hold.
--
-- Why they have to differ: onboarding a client into the matching engine seeds
-- it from this row, and the engine starts with an empty book and therefore no
-- holds. Seeding from a balance that still had holds subtracted lost that
-- margin permanently, with nothing reporting it. Measured on the local stack: a
-- client seeded at 999,999,999,999 read back 999,291,109,999 against 72,002
-- resting orders, and each venue reset would have kept that difference.
--
-- Backfilled from available_balance: for a client with no open orders the two
-- are equal, and for one with open orders this is the same value the seed
-- already used, so the migration changes nothing on its own.
ALTER TABLE portfolio_balance
    ADD COLUMN settled_balance BIGINT NOT NULL DEFAULT 0
        CHECK (settled_balance >= 0);

UPDATE portfolio_balance SET settled_balance = available_balance;

ALTER TABLE received_portfolio_event
    ADD COLUMN change_kind VARCHAR(10) NOT NULL DEFAULT 'SETTLED'
        CHECK (change_kind IN ('SETTLED', 'RESERVED'));

-- Audit reporting asks for completed changes and their confirmed deliveries.
CREATE INDEX idx_received_portfolio_event_settled
    ON received_portfolio_event (client_id, received_at)
    WHERE change_kind = 'SETTLED';
