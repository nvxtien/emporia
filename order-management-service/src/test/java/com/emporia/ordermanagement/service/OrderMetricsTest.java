package com.emporia.ordermanagement.service;

import com.emporia.ordermanagement.repository.TradingOrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderMetricsTest {

    @Test
    void registersOrderMetricsGauges() {
        MeterRegistry meters = new SimpleMeterRegistry();
        OrderMetrics metrics = new OrderMetrics(meters);
        assertThat(metrics).isNotNull();

        metrics.orderCreated();
        metrics.orderCreated();
        metrics.cancelRequested();
        metrics.orderCancelled();
        metrics.orderFilled();

        assertThat(meters.find("emporia.orders.created.total").counter().count()).isEqualTo(2.0);
        assertThat(meters.find("emporia.orders.cancelled.total").counter().count()).isEqualTo(1.0);
        assertThat(meters.find("emporia.orders.filled.total").counter().count()).isEqualTo(1.0);

        assertThat(meters.find("total_orders").gauge().value()).isEqualTo(2.0);
        assertThat(meters.find("live_orders").gauge().value()).isEqualTo(0.0);
        assertThat(meters.find("cancelled_orders").gauge().value()).isEqualTo(1.0);
        assertThat(meters.find("filled_orders").gauge().value()).isEqualTo(1.0);
    }
}
