package com.emporia.execution;

import com.emporia.events.TradingEvents.ExecutionCommand;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionCommandPublisherTest {
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private ExecutionCommandPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new ExecutionCommandPublisher(kafka, "emporia.execution.commands.v1",
                new SimpleMeterRegistry(), ObservationRegistry.NOOP);
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

    @Test
    void publishObservationStopsOnlyAfterKafkaAcknowledges() {
        MeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = observedRegistry(meters);
        ExecutionCommandPublisher observedPublisher = new ExecutionCommandPublisher(kafka,
                "emporia.execution.commands.v1", meters, observations);
        UUID orderId = UUID.randomUUID();
        CompletableFuture<SendResult<String, Object>> send = new CompletableFuture<>();
        when(kafka.send(eq("emporia.execution.commands.v1"), eq(orderId.toString()), any()))
                .thenReturn(send);

        observedPublisher.reject(orderId, "desk-1", "exec-1", "XNAS", "pending");

        assertThat(timerCount(meters, "emporia.execution.publish", "command_type", "reject",
                "outcome", "success")).isZero();

        send.complete(null);

        assertThat(timerCount(meters, "emporia.execution.publish", "command_type", "reject",
                "outcome", "success")).isEqualTo(1);
    }

    @Test
    void publishObservationRecordsAsyncKafkaFailure() {
        MeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = observedRegistry(meters);
        ExecutionCommandPublisher observedPublisher = new ExecutionCommandPublisher(kafka,
                "emporia.execution.commands.v1", meters, observations);
        UUID orderId = UUID.randomUUID();
        CompletableFuture<SendResult<String, Object>> send = new CompletableFuture<>();
        when(kafka.send(eq("emporia.execution.commands.v1"), eq(orderId.toString()), any()))
                .thenReturn(send);

        observedPublisher.reject(orderId, "desk-1", "exec-1", "XNAS", "pending");
        send.completeExceptionally(new IllegalStateException("broker rejected send"));

        assertThat(timerCount(meters, "emporia.execution.publish", "command_type", "reject",
                "outcome", "error")).isEqualTo(1);
    }

    private static ObservationRegistry observedRegistry(MeterRegistry meters) {
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        return observations;
    }

    private static long timerCount(MeterRegistry meters, String name, String... tags) {
        return meters.find(name).tags(tags).timer() == null
                ? 0 : meters.find(name).tags(tags).timer().count();
    }
}
