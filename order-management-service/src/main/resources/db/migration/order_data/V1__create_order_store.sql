CREATE TABLE trading_order (
    id UUID PRIMARY KEY,
    entity_version BIGINT NOT NULL DEFAULT 0,
    user_subject VARCHAR(200) NOT NULL,
    listing_id BIGINT NOT NULL,
    listing_version INTEGER NOT NULL,
    listing_symbol VARCHAR(24) NOT NULL,
    listing_name VARCHAR(200) NOT NULL,
    market_symbol VARCHAR(24) NOT NULL,
    exchange_mic VARCHAR(12) NOT NULL,
    exchange_name VARCHAR(120) NOT NULL,
    country_code VARCHAR(2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    tick_size NUMERIC(19, 6) NOT NULL,
    size_increment NUMERIC(19, 6) NOT NULL,
    reference_price NUMERIC(19, 6) NOT NULL,
    previous_close NUMERIC(19, 6) NOT NULL,
    order_side VARCHAR(8) NOT NULL,
    order_type VARCHAR(16) NOT NULL,
    quantity NUMERIC(19, 6) NOT NULL,
    limit_price NUMERIC(19, 6),
    remaining_quantity NUMERIC(19, 6) NOT NULL,
    traded_quantity NUMERIC(19, 6) NOT NULL,
    average_trade_price NUMERIC(19, 6),
    order_status VARCHAR(24) NOT NULL,
    target_status VARCHAR(24) NOT NULL,
    destination VARCHAR(32) NOT NULL,
    originator_reference VARCHAR(100) NOT NULL,
    parent_order_id UUID,
    root_order_id UUID NOT NULL,
    execution_parameters TEXT,
    error_message VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_trading_order_user_created ON trading_order (user_subject, created_at);
CREATE INDEX idx_trading_order_parent ON trading_order (parent_order_id);

CREATE TABLE order_event (
    id UUID PRIMARY KEY,
    command_id UUID NOT NULL,
    order_id UUID NOT NULL REFERENCES trading_order (id) ON DELETE CASCADE,
    order_version BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    order_status VARCHAR(24) NOT NULL,
    quantity NUMERIC(19, 6) NOT NULL,
    price NUMERIC(19, 6),
    message VARCHAR(500),
    payload TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_order_event_order_time ON order_event (order_id, occurred_at);
CREATE INDEX idx_order_event_command ON order_event (command_id);

CREATE TABLE execution (
    id UUID PRIMARY KEY,
    execution_reference VARCHAR(100) NOT NULL UNIQUE,
    order_id UUID NOT NULL REFERENCES trading_order (id) ON DELETE CASCADE,
    quantity NUMERIC(19, 6) NOT NULL,
    price NUMERIC(19, 6) NOT NULL,
    venue VARCHAR(32) NOT NULL,
    executed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_execution_order_time ON execution (order_id, executed_at);

CREATE TABLE processed_order_command (
    command_id UUID PRIMARY KEY,
    schema_version INTEGER NOT NULL,
    success BOOLEAN NOT NULL,
    http_status INTEGER NOT NULL,
    detail VARCHAR(500),
    payload TEXT,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
