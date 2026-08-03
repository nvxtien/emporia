package com.emporia.ordercommand;

import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderCommandResult;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaCommandGatewayTest {
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final ReplyListenerReadiness readiness = new ReplyListenerReadiness();
    private KafkaCommandGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new KafkaCommandGateway(kafka, "emporia.commands", Duration.ofMillis(200), readiness);
        // Every existing test assumes the reply path is usable; the readiness
        // gate itself is exercised separately below.
        readiness.markAssigned();
    }

    @Test
    void sendSuccess() throws Exception {
        OrderCommand command = command();
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(50);
                gateway.result(new OrderCommandResult(SCHEMA_VERSION, command.commandId(), true, 200, "OK", "{\"status\":\"SUCCESS\"}"));
            } catch (InterruptedException ignored) {}
        });

        String payload = gateway.send(command);
        assertThat(payload).isEqualTo("{\"status\":\"SUCCESS\"}");
    }

    @Test
    void sendReturnsErrorStatus() {
        OrderCommand command = command();
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(50);
                gateway.result(new OrderCommandResult(SCHEMA_VERSION, command.commandId(), false, 400, "Bad Request", null));
            } catch (InterruptedException ignored) {}
        });

        assertThatThrownBy(() -> gateway.send(command))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Bad Request")
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sendTimesOut() {
        OrderCommand command = command();
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        assertThatThrownBy(() -> gateway.send(command))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Kafka command timeout")
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void sendKafkaSendFails() {
        OrderCommand command = command();
        CompletableFuture future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Kafka connection error"));
        when(kafka.send(anyString(), anyString(), any())).thenReturn(future);

        assertThatThrownBy(() -> gateway.send(command))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Kafka is unavailable");
    }

    @Test
    void sendInterrupted() {
        OrderCommand command = command();
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> gateway.send(command))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("interrupted");
    }

    @Test
    void refusesToSendBeforePartitionsAreAssigned() {
        readiness.markRevoked();

        assertThatThrownBy(() -> gateway.send(command()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        // The important half: publishing and *then* failing would create a real
        // order whose reply can never be read, which is the 504 this gate
        // exists to prevent.
        verify(kafka, never()).send(anyString(), anyString(), any());
    }

    @Test
    void partitionAssignmentOpensAndRevocationClosesTheGate() {
        readiness.markRevoked();
        assertThat(readiness.ready()).isFalse();

        gateway.onPartitionsAssigned(Map.of(new TopicPartition("emporia.order.results.v1", 0), 0L), null);
        assertThat(readiness.ready()).isTrue();

        gateway.onPartitionsRevoked(List.of(new TopicPartition("emporia.order.results.v1", 0)));
        assertThat(readiness.ready()).isFalse();
    }

    @Test
    void emptyAssignmentDoesNotOpenTheGate() {
        readiness.markRevoked();

        gateway.onPartitionsAssigned(Map.of(), null);

        assertThat(readiness.ready()).isFalse();
    }

    private static OrderCommand command() {
        return new OrderCommand(SCHEMA_VERSION, UUID.randomUUID(), CommandType.CREATE,
                "user-1", "DESK-A", Instant.now(), UUID.randomUUID(), null, null, null, null,
                null, null, "DMA", "ref-1", null, Map.of());
    }
}
