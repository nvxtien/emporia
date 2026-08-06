-- Migration V5: Add composite indexes for hot-path queries

-- 1. CancelAll query optimization:
-- Optimizes findByDeskIdAndStatusInOrderByCreatedAtDesc used during cancelAll
CREATE INDEX idx_trading_order_desk_status
    ON trading_order (desk_id, order_status, created_at DESC);

-- 2. Execution recovery & parent-child rollup query optimization:
-- Optimizes findByParentOrderIdAndStatusIn used during fill rollup and strategy cancellation checks
CREATE INDEX idx_trading_order_parent_status
    ON trading_order (parent_order_id, order_status);

-- 3. Strategy query optimization (find active root parent orders):
-- Optimizes strategy recovery / monitoring queries filtering active root strategy orders
CREATE INDEX idx_trading_order_active_roots
    ON trading_order (parent_order_id, order_status, destination);
