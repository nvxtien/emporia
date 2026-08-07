package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.ordermanagement.model.OrderOutboxRecord;
import com.emporia.ordermanagement.repository.OrderOutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxDispatcherTest {
    private final OrderOutboxRepository repository = mock(OrderOutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OutboxDispatcher dispatcher =
            new OutboxDispatcher(repository, kafka, objectMapper, new SimpleMeterRegistry());

    @Test
    void publishesAPendingRecordAndMarksItPublished() {
        OrderOutboxRecord record = pendingEventRecord();
        when(repository.findTop500ByStatusOrderBySequenceIdAsc(OrderOutboxRecord.Status.PENDING))
                .thenReturn(List.of(record));
        when(kafka.send(eq("orders-topic"), eq("order-1"), any())).thenReturn(CompletableFuture.completedFuture(null));

        dispatcher.dispatch();

        assertThat(record.getStatus()).isEqualTo(OrderOutboxRecord.Status.PUBLISHED);
        assertThat(record.getPublishedAt()).isNotNull();
        verify(repository).save(record);
    }

    @Test
    void failedSendLeavesTheRecordPendingAndRecordsTheAttempt() {
        OrderOutboxRecord record = pendingEventRecord();
        when(repository.findTop500ByStatusOrderBySequenceIdAsc(OrderOutboxRecord.Status.PENDING))
                .thenReturn(List.of(record));
        when(kafka.send(eq("orders-topic"), eq("order-1"), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")));

        dispatcher.dispatch();

        assertThat(record.getStatus()).isEqualTo(OrderOutboxRecord.Status.PENDING);
        assertThat(record.getAttemptCount()).isEqualTo(1);
        assertThat(record.getLastError()).contains("broker unavailable");
        verify(repository).save(record);
    }

    @Test
    void dispatchIsANoOpWithNothingPending() {
        when(repository.findTop500ByStatusOrderBySequenceIdAsc(OrderOutboxRecord.Status.PENDING))
                .thenReturn(List.of());

        dispatcher.dispatch();

        verify(repository, never()).save(any());
        verify(kafka, never()).send(any(), any(), any());
    }

    private OrderOutboxRecord pendingEventRecord() {
        OrderDomainEvent event = new OrderDomainEvent(SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "trader", "desk-a", "CREATED", 1L, OrderStatus.LIVE, Instant.now(), "{}");
        try {
            String payload = objectMapper.writeValueAsString(event);
            return new OrderOutboxRecord("orders-topic", "order-1", OrderOutboxRecord.PayloadType.ORDER_EVENT, payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize a test event", exception);
        }
    }
}
