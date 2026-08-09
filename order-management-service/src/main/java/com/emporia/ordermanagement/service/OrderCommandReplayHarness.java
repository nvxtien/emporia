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

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;

@Service
public class OrderCommandReplayHarness {
    private static final Logger log = LoggerFactory.getLogger(OrderCommandReplayHarness.class);
    private static final Comparator<OrderInputEvent> BY_SEQUENCE_ID =
            Comparator.comparingLong(OrderInputEvent::getSequenceId);

    private final OrderInputEventRepository inputEvents;
    private final OrderCommandHandler handler;
    private final ObjectMapper objectMapper;
    private final MemoryMappedWalLogger wal;

    OrderCommandReplayHarness(OrderInputEventRepository inputEvents, OrderCommandHandler handler,
                              ObjectMapper objectMapper, MemoryMappedWalLogger wal) {
        this.inputEvents = inputEvents;
        this.handler = handler;
        this.objectMapper = objectMapper;
        this.wal = wal;
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
     * <p>Must run before the pipeline accepts anything, so the process
     * recovers the commands that are already in the WAL before appending new
     * live traffic after the existing frames.
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
            } catch (RuntimeException unreplayable) {
                failed++;
                log.error("Could not replay a write-ahead log record of {} bytes; "
                        + "the order it describes is not recovered", record.length, unreplayable);
            }
        }
        if (failed > 0) {
            log.error("{} of {} write-ahead log record(s) could not be replayed", failed, records.size());
        } else {
            // Replayed records are now handled and queued for AsyncDbWriter.
            // Keep them in the WAL until the async flush drains DB/outbox queues,
            // then compaction can reclaim them safely.
            wal.markSafePoint();
        }
        return outcomes;
    }

    List<ProcessingOutcome> replayAll() {
        return inputEvents.findAllByOrderBySequenceIdAsc().stream()
                .sorted(BY_SEQUENCE_ID)
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
