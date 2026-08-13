ALTER TABLE trading_order
    ADD COLUMN desk_id VARCHAR(100);

UPDATE trading_order
SET desk_id = user_subject
WHERE desk_id IS NULL;

ALTER TABLE trading_order
    ALTER COLUMN desk_id SET NOT NULL;

CREATE INDEX idx_trading_order_desk_created ON trading_order (desk_id, created_at);
