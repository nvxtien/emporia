package com.emporia.execution;

import com.emporia.events.TradingEvents.ExecutionCommand;
import com.emporia.ordermanagement.service.ExecutionCommandHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ExecutionCommandPublisherTest {
    private final ExecutionCommandHandler handler = mock(ExecutionCommandHandler.class);
    private ExecutionCommandPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new ExecutionCommandPublisher(new SimpleMeterRegistry(), ObservationRegistry.NOOP, handler);
    }

    @Test
    void fillDispatchesExecutionCommandToTheHandler() {
        UUID orderId = UUID.randomUUID();
        publisher.fill(orderId, "desk-1", "exec-1", new BigDecimal("10"), new BigDecimal("150.00"), "XNAS", Instant.now());

        ArgumentCaptor<ExecutionCommand> captor = ArgumentCaptor.forClass(ExecutionCommand.class);
        verify(handler).handle(captor.capture());
        ExecutionCommand command = captor.getValue();
        assertThat(command.orderId()).isEqualTo(orderId);
        assertThat(command.quantity()).isEqualByComparingTo("10");
    }

    @Test
    void rejectDispatchesExecutionCommandToTheHandler() {
        UUID orderId = UUID.randomUUID();
        publisher.reject(orderId, "desk-1", "exec-1", "XNAS", "Rejected by venue");

        ArgumentCaptor<ExecutionCommand> captor = ArgumentCaptor.forClass(ExecutionCommand.class);
        verify(handler).handle(captor.capture());
        assertThat(captor.getValue().detail()).isEqualTo("Rejected by venue");
    }

    @Test
    void venueCancelDispatchesExecutionCommandToTheHandler() {
        UUID orderId = UUID.randomUUID();
        publisher.venueCancel(orderId, "desk-1", "exec-1", "XNAS", "Cancelled by venue");

        ArgumentCaptor<ExecutionCommand> captor = ArgumentCaptor.forClass(ExecutionCommand.class);
        verify(handler).handle(captor.capture());
        assertThat(captor.getValue().detail()).isEqualTo("Cancelled by venue");
    }

    @Test
    void publishObservationRecordsSuccessAfterTheHandlerReturns() {
        MeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = observedRegistry(meters);
        ExecutionCommandPublisher observedPublisher = new ExecutionCommandPublisher(meters, observations, handler);

        observedPublisher.reject(UUID.randomUUID(), "desk-1", "exec-1", "XNAS", "pending");

        assertThat(timerCount(meters, "emporia.execution.publish", "command_type", "reject",
                "outcome", "success")).isEqualTo(1);
    }

    @Test
    void publishObservationRecordsErrorAndRethrowsWhenTheHandlerFails() {
        MeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = observedRegistry(meters);
        ExecutionCommandPublisher observedPublisher = new ExecutionCommandPublisher(meters, observations, handler);
        doThrow(new IllegalStateException("state store unavailable")).when(handler).handle(Mockito.any());

        assertThatThrownBy(() -> observedPublisher.reject(UUID.randomUUID(), "desk-1", "exec-1", "XNAS", "pending"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(timerCount(meters, "emporia.execution.publish", "command_type", "reject",
                "outcome", "error")).isEqualTo(1);
    }

    @Test
    void publishThrowsWhenNoHandlerIsConfigured() {
        ExecutionCommandPublisher unconfigured = new ExecutionCommandPublisher(new SimpleMeterRegistry(), ObservationRegistry.NOOP);

        assertThatThrownBy(() -> unconfigured.fill(UUID.randomUUID(), "desk-1", "exec-1",
                new BigDecimal("1"), new BigDecimal("1"), "XNAS", Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no execution command handler is configured");
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
