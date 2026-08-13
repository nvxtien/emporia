CREATE TABLE portfolio_delivery_cursor (
    client_id BIGINT NOT NULL
        REFERENCES portfolio_state (client_id) ON DELETE CASCADE,
    exchange_id VARCHAR(100) NOT NULL,
    last_delivery_id BIGINT NOT NULL CHECK (last_delivery_id >= 0),
    PRIMARY KEY (client_id, exchange_id)
);
