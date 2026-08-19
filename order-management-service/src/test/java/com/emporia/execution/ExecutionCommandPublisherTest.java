package com.emporia.execution;

import com.emporia.events.TradingEvents.ExecutionCommand;
import com.emporia.ordermanagement.disruptor.DisruptorOrderPipeline;
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
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionCommandPublisherTest {
    private final DisruptorOrderPipeline pipeline = mock(DisruptorOrderPipeline.class);
    private ExecutionCommandPublisher publisher;

    @BeforeEach
    void setUp() {
        when(pipeline.submitExecutionCommand(Mockito.any())).thenReturn(CompletableFuture.completedFuture(null));
        publisher = new ExecutionCommandPublisher(new SimpleMeterRegistry(), ObservationRegistry.NOOP, pipeline);
    }

    @Test
    void fillDispatchesExecutionCommandToTheRing() {
        UUID orderId = UUID.randomUUID();
        publisher.fill(orderId, "desk-1", "exec-1", new BigDecimal("10"), new BigDecimal("150.00"), "XNAS", Instant.now());

        ArgumentCaptor<ExecutionCommand> captor = ArgumentCaptor.forClass(ExecutionCommand.class);
        verify(pipeline).submitExecutionCommand(captor.capture());
        ExecutionCommand command = captor.getValue();
        assertThat(command.orderId()).isEqualTo(orderId);
        assertThat(command.quantity()).isEqualByComparingTo("10");
    }

    @Test
    void rejectDispatchesExecutionCommandToTheRing() {
        UUID orderId = UUID.randomUUID();
        publisher.reject(orderId, "desk-1", "exec-1", "XNAS", "Rejected by venue");

        ArgumentCaptor<ExecutionCommand> captor = ArgumentCaptor.forClass(ExecutionCommand.class);
        verify(pipeline).submitExecutionCommand(captor.capture());
        assertThat(captor.getValue().detail()).isEqualTo("Rejected by venue");
    }

    @Test
    void venueCancelDispatchesExecutionCommandToTheRing() {
        UUID orderId = UUID.randomUUID();
        publisher.venueCancel(orderId, "desk-1", "exec-1", "XNAS", "Cancelled by venue");

        ArgumentCaptor<ExecutionCommand> captor = ArgumentCaptor.forClass(ExecutionCommand.class);
        verify(pipeline).submitExecutionCommand(captor.capture());
        assertThat(captor.getValue().detail()).isEqualTo("Cancelled by venue");
    }

    @Test
    void publishObservationRecordsSuccessAfterTheFutureCompletes() {
        MeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = observedRegistry(meters);
        when(pipeline.submitExecutionCommand(Mockito.any())).thenReturn(CompletableFuture.completedFuture(null));
        ExecutionCommandPublisher observedPublisher = new ExecutionCommandPublisher(meters, observations, pipeline);

        observedPublisher.reject(UUID.randomUUID(), "desk-1", "exec-1", "XNAS", "pending");

        assertThat(timerCount(meters, "emporia.execution.publish", "command_type", "reject",
                "outcome", "success")).isEqualTo(1);
    }

    /**
     * The behavior task 5.1 is built to preserve: a failure applying the
     * command - here simulated the way the ring's event handler actually
     * fails a caller's future, wrapped in CompletionException by join() - must
     * still reach this method's caller as the original exception type, not a
     * CompletionException. ExchangeCoreExecutionVenueGateway's own try/catch
     * around commands.fill/reject/venueCancel depends on that.
     */
    @Test
    void publishObservationRecordsErrorAndRethrowsTheOriginalExceptionWhenTheFutureFails() {
        MeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = observedRegistry(meters);
        IllegalStateException original = new IllegalStateException("state store unavailable");
        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(original);
        when(pipeline.submitExecutionCommand(Mockito.any())).thenReturn(failed);
        ExecutionCommandPublisher observedPublisher = new ExecutionCommandPublisher(meters, observations, pipeline);

        assertThatThrownBy(() -> observedPublisher.reject(UUID.randomUUID(), "desk-1", "exec-1", "XNAS", "pending"))
                .isSameAs(original)
                .as("join()'s CompletionException wrapper must be unwrapped, not leaked to the caller");

        assertThat(timerCount(meters, "emporia.execution.publish", "command_type", "reject",
                "outcome", "error")).isEqualTo(1);
    }

    @Test
    void publishThrowsWhenNoRingIsConfigured() {
        ExecutionCommandPublisher unconfigured = new ExecutionCommandPublisher(new SimpleMeterRegistry(), ObservationRegistry.NOOP);

        assertThatThrownBy(() -> unconfigured.fill(UUID.randomUUID(), "desk-1", "exec-1",
                new BigDecimal("1"), new BigDecimal("1"), "XNAS", Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no OMS ring is configured");
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
