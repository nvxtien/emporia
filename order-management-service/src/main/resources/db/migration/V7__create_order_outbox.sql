CREATE TABLE order_outbox (
    sequence_id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(200) NOT NULL,
    routing_key VARCHAR(200) NOT NULL,
    payload_type VARCHAR(20) NOT NULL
        CHECK (payload_type IN ('ORDER_EVENT', 'ORDER_RESULT')),
    payload TEXT NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PUBLISHED')),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_order_outbox_pending ON order_outbox (status, sequence_id);
