ALTER TABLE trading_order
    ADD CONSTRAINT ck_trading_order_quantity_positive
        CHECK (quantity > 0);

ALTER TABLE trading_order
    ADD CONSTRAINT ck_trading_order_size_increment_positive
        CHECK (size_increment > 0);

ALTER TABLE trading_order
    ADD CONSTRAINT ck_trading_order_quantity_increment
        CHECK (
            CASE
                WHEN size_increment > 0 THEN mod(quantity, size_increment) = 0
                ELSE FALSE
            END
        );

ALTER TABLE trading_order
    ADD CONSTRAINT ck_trading_order_tick_size_positive
        CHECK (tick_size > 0);

ALTER TABLE trading_order
    ADD CONSTRAINT ck_trading_order_fill_accounting
        CHECK (
            traded_quantity >= 0
            AND remaining_quantity >= 0
            AND traded_quantity <= quantity
            AND traded_quantity + remaining_quantity = quantity
        );

ALTER TABLE trading_order
    ADD CONSTRAINT ck_trading_order_price_by_type
        CHECK (
            CASE order_type
                WHEN 'MARKET' THEN limit_price IS NULL
                WHEN 'LIMIT' THEN
                    limit_price > 0
                    AND tick_size > 0
                    AND mod(limit_price, tick_size) = 0
                ELSE FALSE
            END
        );

ALTER TABLE trading_order
    ADD CONSTRAINT ck_trading_order_average_price_positive
        CHECK (average_trade_price IS NULL OR average_trade_price > 0);

ALTER TABLE trading_order
    ADD CONSTRAINT ck_trading_order_side
        CHECK (order_side = 'BUY' OR order_side = 'SELL');

ALTER TABLE trading_order
    ADD CONSTRAINT ck_trading_order_status
        CHECK (
            (
                order_status = 'LIVE'
                OR order_status = 'PARTIALLY_FILLED'
                OR order_status = 'FILLED'
                OR order_status = 'CANCELLED'
                OR order_status = 'REJECTED'
            )
            AND (
                target_status = 'LIVE'
                OR target_status = 'PARTIALLY_FILLED'
                OR target_status = 'FILLED'
                OR target_status = 'CANCELLED'
                OR target_status = 'REJECTED'
            )
        );

ALTER TABLE trading_order
    ADD CONSTRAINT ck_trading_order_status_accounting
        CHECK (
            CASE order_status
                WHEN 'LIVE' THEN traded_quantity = 0 AND remaining_quantity = quantity
                WHEN 'PARTIALLY_FILLED' THEN traded_quantity > 0 AND remaining_quantity > 0
                WHEN 'FILLED' THEN traded_quantity = quantity AND remaining_quantity = 0
                WHEN 'CANCELLED' THEN traded_quantity < quantity AND remaining_quantity > 0
                WHEN 'REJECTED' THEN traded_quantity = 0 AND remaining_quantity = quantity
                ELSE FALSE
            END
        );
