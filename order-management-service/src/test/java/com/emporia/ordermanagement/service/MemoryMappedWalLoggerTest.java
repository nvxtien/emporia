package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents;
import com.emporia.events.sbe.SbeEncoderDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryMappedWalLoggerTest {

    @Test
    void appendsSbeBinaryPayloadToMemoryMappedFile(@TempDir Path tempDir) {
        Path walFile = tempDir.resolve("wal-test.log");
        try (MemoryMappedWalLogger logger = new MemoryMappedWalLogger(walFile.toString(), 1)) {
            assertThat(logger.isEnabled()).isTrue();

            OrderDomainEvent event = new OrderDomainEvent(
                    1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "trader-1", "DESK-A", "CREATED", 1L,
                    OrderStatus.LIVE, Instant.now(), "{}"
            );
            byte[] binary = SbeEncoderDecoder.encodeOrderDomainEvent(event);

            boolean appendSuccess = logger.append(binary);
            assertThat(appendSuccess).isTrue();
            assertThat(logger.position()).isGreaterThan(binary.length);

            logger.force();
        }
    }

    @Test
    void keepsRecordsThatArrivedAfterTheSafePoint() {
        try (MemoryMappedWalLogger logger = new MemoryMappedWalLogger(walPath("compact"), 1)) {
            logger.append(new byte[]{1, 2, 3});
            logger.markSafePoint();
            int handled = logger.position();

            // Accepted while the flush was running: not yet persisted, so its
            // record is the one that would recover it and must be kept.
            logger.append(new byte[]{4, 5, 6, 7});
            int inFlight = logger.position() - handled;

            int reclaimed = logger.compactToSafePoint();

            assertThat(reclaimed).isEqualTo(handled);
            assertThat(logger.position()).isEqualTo(inFlight);
        }
    }

    @Test
    void staysBoundedUnderContinuousTrafficWithoutAQuietMoment() {
        try (MemoryMappedWalLogger logger = new MemoryMappedWalLogger(walPath("bounded"), 1)) {
            byte[] record = new byte[8 * 1024];

            // Every cycle appends after the safe point is taken, so there is
            // never an instant when nothing arrived during a flush. Rewinding
            // needs exactly that instant, which is why it was not enough: the
            // log would climb to its 1 MB limit within ~128 cycles and start
            // refusing orders. Well over a thousand here.
            for (int cycle = 0; cycle < 1_000; cycle++) {
                assertThat(logger.append(record)).isTrue();
                logger.markSafePoint();
                assertThat(logger.append(record)).isTrue();
                logger.compactToSafePoint();
            }

            // Only the single unpersisted record remains, whatever the volume.
            assertThat(logger.position()).isEqualTo(4 + record.length);
        }
    }

    private static String walPath(String name) {
        try {
            return java.nio.file.Files.createTempDirectory("wal-" + name)
                    .resolve(name + ".log").toString();
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
    }

    @Test
    void disabledWhenNoPathConfigured() {
        try (MemoryMappedWalLogger logger = new MemoryMappedWalLogger(null, 1)) {
            assertThat(logger.isEnabled()).isFalse();
            assertThat(logger.append(new byte[]{1, 2, 3})).isFalse();
        }
    }

    @Test
    void readsBackWhatWasWrittenSoTheLogCanBeReplayed() {
        String path = walPath("readback");
        TradingEvents.OrderCommand command = new TradingEvents.OrderCommand(
                TradingEvents.SCHEMA_VERSION, UUID.randomUUID(), TradingEvents.CommandType.CREATE,
                "trader-a", "desk-a", Instant.ofEpochMilli(1_780_000_000_000L),
                UUID.randomUUID(), null, null, TradingEvents.OrderSide.BUY,
                TradingEvents.OrderType.LIMIT, new java.math.BigDecimal("5.000000"),
                new java.math.BigDecimal("99.500000"), "DMA", "ref-1", null, java.util.Map.of());

        try (MemoryMappedWalLogger writer = new MemoryMappedWalLogger(path, 1)) {
            writer.append(SbeEncoderDecoder.encodeOrderCommand(command));
        }

        // A separate mapping, as a restarted process would open. A log that
        // cannot be read back buys nothing.
        try (MemoryMappedWalLogger reader = new MemoryMappedWalLogger(path, 1)) {
            java.util.List<byte[]> records = reader.readPendingRecords();

            assertThat(records).hasSize(1);
            assertThat(SbeEncoderDecoder.decodeOrderCommand(records.getFirst())).isEqualTo(command);
        }
    }

    @Test
    void reopenedWalAppendsAfterReplayedRecordsThatAreNotFlushedYet() {
        String path = walPath("replay-overwrite");
        TradingEvents.OrderCommand replayed = TestCommands.command(UUID.randomUUID());
        TradingEvents.OrderCommand live = TestCommands.command(UUID.randomUUID());

        try (MemoryMappedWalLogger crashedProcess = new MemoryMappedWalLogger(path, 1)) {
            crashedProcess.append(SbeEncoderDecoder.encodeOrderCommand(replayed));
            crashedProcess.force();
        }

        try (MemoryMappedWalLogger restartedProcess = new MemoryMappedWalLogger(path, 1)) {
            assertThat(restartedProcess.readPendingRecords()).hasSize(1);

            // This is the dangerous production window: startup replay has called
            // OrderCommandHandler.handle(...), which only enqueues async DB/outbox
            // writes. Before AsyncDbWriter flushes those queues, the Disruptor
            // starts accepting live commands and appends the next one.
            restartedProcess.append(SbeEncoderDecoder.encodeOrderCommand(live));
            restartedProcess.force();
        }

        try (MemoryMappedWalLogger secondRestart = new MemoryMappedWalLogger(path, 1)) {
            java.util.List<TradingEvents.OrderCommand> recovered = secondRestart.readPendingRecords().stream()
                    .map(SbeEncoderDecoder::decodeOrderCommand)
                    .toList();

            assertThat(recovered)
                    .extracting(TradingEvents.OrderCommand::commandId)
                    .containsExactly(replayed.commandId(), live.commandId());
        }
    }

    @Test
    void doesNotReplayRecordsThatCompactionAlreadyReclaimed() {
        String path = walPath("no-stale");
        byte[] persisted = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        byte[] inFlight = new byte[]{9, 9, 9};

        try (MemoryMappedWalLogger writer = new MemoryMappedWalLogger(path, 1)) {
            writer.append(persisted);
            writer.markSafePoint();
            writer.append(inFlight);
            writer.compactToSafePoint();
        }

        try (MemoryMappedWalLogger reader = new MemoryMappedWalLogger(path, 1)) {
            java.util.List<byte[]> records = reader.readPendingRecords();

            // Only the unpersisted record survives. Compaction moves the tail
            // forward and leaves the old bytes behind it; without blanking them
            // a reader would find intact frames and reapply commands the
            // database already has.
            assertThat(records).hasSize(1);
            assertThat(records.getFirst()).isEqualTo(inFlight);
        }
    }

}
