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

import java.util.List;
import java.util.Comparator;

@Service
class OrderCommandReplayHarness {
    private static final Logger log = LoggerFactory.getLogger(OrderCommandReplayHarness.class);

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
     * <p>Must run before the pipeline accepts anything, since appending starts
     * again at the beginning of the file.
     */
    List<ProcessingOutcome> replayWriteAheadLog() {
        if (wal == null || !wal.isEnabled()) return List.of();
        List<byte[]> records = wal.readPendingRecords();
        if (records.isEmpty()) return List.of();
        log.info("Replaying {} order command(s) from the write-ahead log", records.size());
        return records.stream().map(this::replayRecord).toList();
    }

    private ProcessingOutcome replayRecord(byte[] record) {
        try {
            return handler.handle(SbeEncoderDecoder.decodeOrderCommand(record));
        } catch (RuntimeException undecodable) {
            throw new IllegalStateException(
                    "Could not replay a write-ahead log record of " + record.length + " bytes",
                    undecodable);
        }
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