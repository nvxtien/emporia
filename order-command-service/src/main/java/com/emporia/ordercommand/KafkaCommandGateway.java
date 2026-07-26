package com.emporia.ordercommand;

import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderCommandResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
class KafkaCommandGateway {
    private final KafkaTemplate<String, Object> kafka;
    private final ConcurrentHashMap<UUID, CompletableFuture<OrderCommandResult>> pending = new ConcurrentHashMap<>();
    private final String commandsTopic;
    private final Duration timeout;

    KafkaCommandGateway(KafkaTemplate<String, Object> kafka,
                        @Value("${emporia.kafka.commands-topic}") String commandsTopic,
                        @Value("${emporia.kafka.command-timeout:8s}") Duration timeout) {
        this.kafka = kafka;
        this.commandsTopic = commandsTopic;
        this.timeout = timeout;
    }

    String send(OrderCommand command) {
        CompletableFuture<OrderCommandResult> response = new CompletableFuture<>();
        pending.put(command.commandId(), response);
        kafka.send(commandsTopic, command.orderId() == null ? command.userSubject() : command.orderId().toString(), command)
                .whenComplete((ignored, error) -> {
                    if (error != null) response.completeExceptionally(error);
                });
        try {
            OrderCommandResult result = response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!result.success()) {
                throw new ResponseStatusException(HttpStatus.valueOf(result.status()), result.detail());
            }
            return result.payload();
        } catch (TimeoutException exception) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "The order processor did not answer before the Kafka command timeout", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Order command interrupted", exception);
        } catch (ExecutionException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Kafka is unavailable", exception);
        } finally {
            pending.remove(command.commandId());
        }
    }

    @KafkaListener(topics = "${emporia.kafka.results-topic}", groupId = "${spring.application.name}-${random.uuid}")
    void result(OrderCommandResult result) {
        CompletableFuture<OrderCommandResult> response = pending.get(result.commandId());
        if (response != null) response.complete(result);
    }
}
