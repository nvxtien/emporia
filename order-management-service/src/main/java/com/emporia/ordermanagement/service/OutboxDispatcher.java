package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.ordermanagement.model.OrderOutboxRecord;
import com.emporia.ordermanagement.repository.OrderOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Drains {@link OrderOutboxRecord} rows written in the same transaction that
 * made the underlying order/execution durable, so a crash between "durable"
 * and "published" leaves a row here to retry rather than an event nobody ever
 * sends. Single instance, no lease: the Disruptor ring and the mmap WAL are
 * already process-local, so OMS never runs more than one of these at a time.
 */
@Service
public class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OrderOutboxRepository repository;
    private final KafkaTemplate<String, Object> kafka;
    private final ObjectMapper objectMapper;
    private final Counter dispatchFailures;

    public OutboxDispatcher(OrderOutboxRepository repository, KafkaTemplate<String, Object> kafka,
                            ObjectMapper objectMapper, MeterRegistry meters) {
        this.repository = repository;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.dispatchFailures = meters.counter("emporia.oms.outbox.dispatch.failures");
    }

    @Scheduled(fixedDelayString = "${emporia.outbox.dispatch-delay-ms:50}")
    public void dispatch() {
        for (OrderOutboxRecord record : repository.findTop500ByStatusOrderBySequenceIdAsc(OrderOutboxRecord.Status.PENDING)) {
            publishOne(record);
        }
    }

    private void publishOne(OrderOutboxRecord record) {
        Object payload;
        try {
            payload = decode(record);
        } catch (RuntimeException malformed) {
            // A record this build cannot decode will never decode later either;
            // recording the failure without leaving it PENDING-forever-silent
            // keeps one bad row from masking every row behind it.
            dispatchFailures.increment();
            record.markFailed("decode: " + malformed.getMessage());
            repository.save(record);
            log.error("Could not decode outbox record {}; will retry", record.getSequenceId(), malformed);
            return;
        }

        try {
            kafka.send(record.getTopic(), record.getRoutingKey(), payload).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            record.markPublished(Instant.now());
        } catch (Exception sendFailure) {
            dispatchFailures.increment();
            record.markFailed(sendFailure.getClass().getSimpleName() + ": " + sendFailure.getMessage());
            log.warn("Outbox record {} did not publish to {} (attempt {})", record.getSequenceId(),
                    record.getTopic(), record.getAttemptCount(), sendFailure);
        }
        repository.save(record);
    }

    private Object decode(OrderOutboxRecord record) {
        return switch (record.getPayloadType()) {
            case ORDER_EVENT -> objectMapper.readValue(record.getPayload(), OrderDomainEvent.class);
            case ORDER_RESULT -> objectMapper.readValue(record.getPayload(), OrderCommandResult.class);
        };
    }
}
