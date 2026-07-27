CREATE TABLE portfolio_state (
    client_id BIGINT PRIMARY KEY CHECK (client_id > 0),
    first_transaction_id BIGINT NOT NULL
        CHECK (first_transaction_id > 0),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE portfolio_balance (
    client_id BIGINT NOT NULL
        REFERENCES portfolio_state (client_id) ON DELETE CASCADE,
    asset_id INTEGER NOT NULL CHECK (asset_id >= 0),
    available_balance BIGINT NOT NULL CHECK (available_balance >= 0),
    PRIMARY KEY (client_id, asset_id)
);

CREATE TABLE received_portfolio_event (
    event_id VARCHAR(240) PRIMARY KEY,
    exchange_id VARCHAR(100) NOT NULL,
    delivery_id BIGINT NOT NULL CHECK (delivery_id >= 0),
    client_id BIGINT NOT NULL
        REFERENCES portfolio_state (client_id),
    payload_sha256 CHAR(64) NOT NULL
        CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    payload BYTEA NOT NULL CHECK (octet_length(payload) > 0),
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (exchange_id, delivery_id, client_id)
);

CREATE INDEX idx_received_portfolio_event_client_time
    ON received_portfolio_event (client_id, received_at);
