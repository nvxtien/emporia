CREATE TABLE instrument_listing (
    id BIGINT PRIMARY KEY,
    version INTEGER NOT NULL DEFAULT 1,
    symbol VARCHAR(24) NOT NULL,
    name VARCHAR(200) NOT NULL,
    market_symbol VARCHAR(24) NOT NULL,
    exchange_mic VARCHAR(12) NOT NULL,
    exchange_name VARCHAR(120) NOT NULL,
    country_code VARCHAR(2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    tick_size NUMERIC(19, 6) NOT NULL,
    size_increment NUMERIC(19, 6) NOT NULL,
    reference_price NUMERIC(19, 6) NOT NULL,
    previous_close NUMERIC(19, 6) NOT NULL,
    UNIQUE (market_symbol, exchange_mic)
);

CREATE INDEX idx_instrument_listing_symbol ON instrument_listing (symbol);
CREATE INDEX idx_instrument_listing_name ON instrument_listing (name);
