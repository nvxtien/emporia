CREATE TABLE exchange_core_portfolio_outbox (
    sequence_id BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE NOT NULL,
    event_id VARCHAR(240) PRIMARY KEY,
    exchange_id VARCHAR(100) NOT NULL,
    delivery_id BIGINT NOT NULL CHECK (delivery_id >= 0),
    client_id BIGINT NOT NULL CHECK (client_id > 0),
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    payload BYTEA NOT NULL CHECK (octet_length(payload) > 0),
    payload_sha256 CHAR(64) NOT NULL
        CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN (
            'PENDING',
            'IN_FLIGHT',
            'RETRY',
            'PUBLISHED',
            'DEAD'
        )),
    attempt_count INTEGER NOT NULL DEFAULT 0
        CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    lease_owner VARCHAR(100),
    lease_until TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    published_at TIMESTAMP WITH TIME ZONE,
    CHECK (
        (
            status = 'IN_FLIGHT'
            AND lease_owner IS NOT NULL
            AND lease_until IS NOT NULL
        )
        OR (
            status <> 'IN_FLIGHT'
            AND lease_owner IS NULL
            AND lease_until IS NULL
        )
    ),
    CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (status <> 'PUBLISHED' AND published_at IS NULL)
    ),
    UNIQUE (exchange_id, delivery_id, client_id)
);

CREATE INDEX idx_exchange_core_portfolio_outbox_ready
    ON exchange_core_portfolio_outbox (next_attempt_at, sequence_id)
    WHERE status IN ('PENDING', 'RETRY', 'IN_FLIGHT');
