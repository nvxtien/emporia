package com.emporia.ordercommand;

import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderCommandResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
class KafkaCommandGateway implements ConsumerSeekAware {
    private final KafkaTemplate<String, Object> kafka;
    private final ConcurrentHashMap<UUID, CompletableFuture<OrderCommandResult>> pending = new ConcurrentHashMap<>();
    private final String commandsTopic;
    private final Duration timeout;
    private final ReplyListenerReadiness readiness;

    KafkaCommandGateway(KafkaTemplate<String, Object> kafka,
                        @Value("${emporia.kafka.commands-topic}") String commandsTopic,
                        @Value("${emporia.kafka.command-timeout:8s}") Duration timeout,
                        ReplyListenerReadiness readiness) {
        this.kafka = kafka;
        this.commandsTopic = commandsTopic;
        this.timeout = timeout;
        this.readiness = readiness;
    }

    String send(OrderCommand command) {
        // Checked before publishing, not after. Until the reply listener holds
        // its partitions a reply cannot be read at all, so publishing here would
        // create a real order whose result is lost - the caller then gets a 504
        // for an order that succeeded. Failing fast with a retryable 503 is both
        // honest and safe; the 504 is neither.
        if (!readiness.ready()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The order reply listener has not been assigned its partitions yet");
        }
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

    /**
     * Marks the reply path usable once this listener owns partitions.
     *
     * <p>Spring Kafka publishes no partitions-assigned application event, so
     * this uses the ConsumerSeekAware callbacks, which the container invokes on
     * the listener bean itself.
     */
    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        if (!assignments.isEmpty()) {
            readiness.markAssigned();
        }
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        readiness.markRevoked();
    }
}
