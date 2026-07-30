package com.emporia.execution;

import com.emporia.events.TradingEvents.ExecutionCommand;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ExecutionCommandPublisherTest {
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private ExecutionCommandPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new ExecutionCommandPublisher(kafka, "emporia.execution.commands.v1", new SimpleMeterRegistry());
    }

    @Test
    void fillPublishesExecutionCommand() {
        UUID orderId = UUID.randomUUID();
        publisher.fill(orderId, "desk-1", "exec-1", new BigDecimal("10"), new BigDecimal("150.00"), "XNAS", Instant.now());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq("emporia.execution.commands.v1"), eq(orderId.toString()), captor.capture());
        ExecutionCommand command = (ExecutionCommand) captor.getValue();
        assertThat(command.orderId()).isEqualTo(orderId);
        assertThat(command.quantity()).isEqualByComparingTo("10");
    }

    @Test
    void rejectPublishesExecutionCommand() {
        UUID orderId = UUID.randomUUID();
        publisher.reject(orderId, "desk-1", "exec-1", "XNAS", "Rejected by venue");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq("emporia.execution.commands.v1"), eq(orderId.toString()), captor.capture());
        ExecutionCommand command = (ExecutionCommand) captor.getValue();
        assertThat(command.detail()).isEqualTo("Rejected by venue");
    }

    @Test
    void venueCancelPublishesExecutionCommand() {
        UUID orderId = UUID.randomUUID();
        publisher.venueCancel(orderId, "desk-1", "exec-1", "XNAS", "Cancelled by venue");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq("emporia.execution.commands.v1"), eq(orderId.toString()), captor.capture());
        ExecutionCommand command = (ExecutionCommand) captor.getValue();
        assertThat(command.detail()).isEqualTo("Cancelled by venue");
    }
}
