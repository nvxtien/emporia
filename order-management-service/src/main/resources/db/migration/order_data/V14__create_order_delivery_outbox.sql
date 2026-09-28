CREATE TABLE order_delivery_outbox (
    sequence_id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    schema_version INTEGER NOT NULL,
    command_id UUID NOT NULL,
    order_id UUID NOT NULL,
    user_subject VARCHAR(200),
    desk_id VARCHAR(100),
    event_type VARCHAR(32) NOT NULL,
    order_version BIGINT NOT NULL,
    order_status VARCHAR(24) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'DELIVERED')),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_until TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(2000),
    delivered_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_order_delivery_outbox_pending
    ON order_delivery_outbox (status, next_attempt_at, sequence_id);
