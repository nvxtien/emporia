package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.sbe.SbeEncoderDecoder;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
import com.emporia.ordermanagement.model.OrderInputEvent;
import com.emporia.ordermanagement.repository.OrderInputEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.KafkaRoutingKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;

@Service
public class OrderCommandReplayHarness {
    private static final Logger log = LoggerFactory.getLogger(OrderCommandReplayHarness.class);

    private final OrderInputEventRepository inputEvents;
    private final OrderCommandHandler handler;
    private final ObjectMapper objectMapper;
    private final MemoryMappedWalLogger wal;
    private final KafkaTemplate<String, Object> kafka;
    private final String ordersTopic;
    private final String resultsTopic;

    OrderCommandReplayHarness(OrderInputEventRepository inputEvents, OrderCommandHandler handler,
                              ObjectMapper objectMapper, MemoryMappedWalLogger wal) {
        this(inputEvents, handler, objectMapper, wal, null, "emporia.orders.v1", "emporia.order.results.v1");
    }

    @Autowired
    public OrderCommandReplayHarness(OrderInputEventRepository inputEvents, OrderCommandHandler handler,
                                      ObjectMapper objectMapper, MemoryMappedWalLogger wal,
                                      @Autowired(required = false) KafkaTemplate<String, Object> kafka,
                                      @Value("${emporia.kafka.orders-topic:emporia.orders.v1}") String ordersTopic,
                                      @Value("${emporia.kafka.results-topic:emporia.order.results.v1}") String resultsTopic) {
        this.inputEvents = inputEvents;
        this.handler = handler;
        this.objectMapper = objectMapper;
        this.wal = wal;
        this.kafka = kafka;
        this.ordersTopic = ordersTopic;
        this.resultsTopic = resultsTopic;
    }

    /**
     * Reapplies the commands the write-ahead log still holds.
     *
     * <p>These are the orders accepted on the ring but not yet written by
     * AsyncDbWriter when the process stopped - the window the log exists to
     * cover, and the one the database has no record of. Without this the log is
     * only written, never read, and buys nothing.
     *
     * <p>Safe to run against records that did reach the database: the handler
     * deduplicates on command id, so a command persisted just before the stop is
     * recognised rather than applied twice. Ordering is the order they were
     * accepted in, which is the order the log holds them.
     *
     * <p>Must run before the pipeline accepts anything, since appending starts
     * again at the beginning of the file.
     */
    public List<ProcessingOutcome> replayWriteAheadLog() {
        if (wal == null || !wal.isEnabled()) return List.of();
        List<byte[]> records = wal.readPendingRecords();
        if (records.isEmpty()) return List.of();
        log.info("Replaying {} order command(s) from the write-ahead log", records.size());

        List<ProcessingOutcome> outcomes = new ArrayList<>(records.size());
        int failed = 0;
        for (byte[] record : records) {
            try {
                OrderCommand decodedCommand = SbeEncoderDecoder.decodeOrderCommand(record);
                ProcessingOutcome outcome = handler.handle(decodedCommand);
                outcomes.add(outcome);

                if (kafka != null && outcome != null && outcome.result() != null && outcome.result().success()) {
                    for (OrderDomainEvent event : outcome.events()) {
                        kafka.send(ordersTopic, KafkaRoutingKeys.orderEvent(event), event);
                    }
                    kafka.send(resultsTopic, KafkaRoutingKeys.orderResult(decodedCommand), outcome.result());
                }
            } catch (RuntimeException unreplayable) {
                failed++;
                log.error("Could not replay a write-ahead log record of {} bytes; "
                        + "the order it describes is not recovered", record.length, unreplayable);
            }
        }
        if (failed > 0) {
            log.error("{} of {} write-ahead log record(s) could not be replayed", failed, records.size());
        }
        return outcomes;
    }

    List<ProcessingOutcome> replayAll() {
        return inputEvents.findAllByOrderBySequenceIdAsc().stream()
                .sorted(Comparator.comparingLong(OrderInputEvent::getSequenceId))
                .map(this::replay)
                .toList();
    }

    ProcessingOutcome replay(OrderInputEvent inputEvent) {
        try {
            OrderCommand command = objectMapper.readValue(inputEvent.getPayload(), OrderCommand.class);
            return handler.handle(command);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not replay order input sequence " + inputEvent.getSequenceId(), exception);
        }
    }
}