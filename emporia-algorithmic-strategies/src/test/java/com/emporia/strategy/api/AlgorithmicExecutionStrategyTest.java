package com.emporia.strategy.api;

import com.emporia.events.TradingEvents.OrderSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AlgorithmicExecutionStrategyTest {

    @Test
    void executionPlanRecordBehaviors() {
        AlgorithmicExecutionStrategy.ExecutionPlan plan1 = new AlgorithmicExecutionStrategy.ExecutionPlan(
                "SMART", OrderSide.BUY, new BigDecimal("100.50"), true);
        AlgorithmicExecutionStrategy.ExecutionPlan plan2 = new AlgorithmicExecutionStrategy.ExecutionPlan(
                "SMART", OrderSide.BUY, new BigDecimal("100.50"), true);

        assertThat(plan1).isEqualTo(plan2);
        assertThat(plan1.hashCode()).isEqualTo(plan2.hashCode());
        assertThat(plan1.toString()).contains("SMART");
        assertThat(plan1.strategyName()).isEqualTo("SMART");
        assertThat(plan1.side()).isEqualTo(OrderSide.BUY);
        assertThat(plan1.limitPrice()).isEqualTo(new BigDecimal("100.50"));
        assertThat(plan1.isExecutable()).isTrue();
    }
}
