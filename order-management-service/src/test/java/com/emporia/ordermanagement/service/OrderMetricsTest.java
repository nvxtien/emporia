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
        TradingOrderRepository orders = mock(TradingOrderRepository.class);

        when(orders.count()).thenReturn(10L);
        when(orders.countByStatusIn(any())).thenReturn(4L);
        when(orders.countByStatus(any())).thenReturn(2L);
        when(orders.countByTargetStatusAndStatusIn(any(), any())).thenReturn(1L);

        OrderMetrics metrics = new OrderMetrics(meters, orders);
        assertThat(metrics).isNotNull();

        assertThat(meters.find("total_orders").gauge().value()).isEqualTo(10.0);
        assertThat(meters.find("live_orders").gauge().value()).isEqualTo(4.0);
        assertThat(meters.find("cancelled_orders").gauge().value()).isEqualTo(2.0);
        assertThat(meters.find("filled_orders").gauge().value()).isEqualTo(2.0);
        assertThat(meters.find("rejected_orders").gauge().value()).isEqualTo(2.0);
        assertThat(meters.find("none_status_orders").gauge().value()).isEqualTo(0.0);
        assertThat(meters.find("pending_live_orders").gauge().value()).isEqualTo(0.0);
        assertThat(meters.find("pending_cancel_orders").gauge().value()).isEqualTo(1.0);
    }
}
