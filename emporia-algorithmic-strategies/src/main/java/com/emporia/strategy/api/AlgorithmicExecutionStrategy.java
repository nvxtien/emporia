package com.emporia.strategy.api;

import com.emporia.events.TradingEvents.OrderSide;
import java.math.BigDecimal;

/**
 * Standard SPI contract for pure domain Algorithmic Trading Execution Strategies.
 */
public interface AlgorithmicExecutionStrategy {
    
    /**
     * Unique identifier of the execution strategy (e.g. "DMA", "SMART", "VWAP", "TWAP").
     */
    String strategyName();

    /**
     * Container holding plan outcomes for routed strategy execution steps.
     */
    record ExecutionPlan(
            String strategyName,
            OrderSide side,
            BigDecimal limitPrice,
            boolean isExecutable
    ) {}
}
