ALTER TABLE trading_order
    ADD CONSTRAINT ck_trading_order_target_transition
        CHECK (
            target_status = order_status
            OR (
                target_status = 'LIVE'
                AND (order_status = 'LIVE' OR order_status = 'PARTIALLY_FILLED')
            )
            OR (
                target_status = 'CANCELLED'
                AND (order_status = 'LIVE' OR order_status = 'PARTIALLY_FILLED')
            )
        );
