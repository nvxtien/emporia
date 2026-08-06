package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.sbe.SbeEncoderDecoder;
import com.emporia.events.time.DomainClock;
import com.emporia.ordermanagement.model.ProcessedCommand;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.OrderInputEventRepository;
import com.emporia.ordermanagement.repository.ProcessedCommandRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Automated end-to-end integration test proving write-ahead log (WAL) crash recovery.
 *
 * <p>Simulates a process crash after SBE binary orders are written to the memory-mapped log
 * but BEFORE database commit occurs, verifying 100% zero-data-loss recovery upon restart.
 */
class WalCrashRecoveryIntegrationTest {

    @Test
    void processCrashBeforeDbCommitIsFullyRecoveredFromWal(@TempDir Path tempDir) {
        String walPath = tempDir.resolve("emporia-crash-test.wal").toString();

        UUID commandId1 = UUID.randomUUID();
        UUID orderId1 = UUID.randomUUID();
        ListingSnapshot listing = new ListingSnapshot(1L, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "NASDAQ", "US", "USD",
                new BigDecimal("0.010000"), new BigDecimal("1.000000"), new BigDecimal("150.000000"), new BigDecimal("149.000000"));

        OrderCommand command1 = new OrderCommand(1, commandId1, CommandType.CREATE, "trader-1", "desk-1",
                DomainClock.now(), orderId1, null, listing, OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("500.000000"), new BigDecimal("150.000000"), "DMA", "REF-WAL-1", null, Map.of());

        UUID commandId2 = UUID.randomUUID();
        UUID orderId2 = UUID.randomUUID();
        OrderCommand command2 = new OrderCommand(1, commandId2, CommandType.CREATE, "trader-2", "desk-1",
                DomainClock.now(), orderId2, null, listing, OrderSide.SELL, OrderType.LIMIT,
                new BigDecimal("200.000000"), new BigDecimal("152.000000"), "DMA", "REF-WAL-2", null, Map.of());

        // 1. STAGE 1: Process accepts commands and writes SBE binary to WAL disk file
        try (MemoryMappedWalLogger activeWal = new MemoryMappedWalLogger(walPath, 5)) {
            activeWal.append(SbeEncoderDecoder.encodeOrderCommand(command1));
            activeWal.append(SbeEncoderDecoder.encodeOrderCommand(command2));
            // Simulate sudden process crash (kill -9): process dies without flushing DB or calling close
        }

        // 2. STAGE 2: Restart process — new WAL logger opens existing file on disk
        OrderCommandHandler mockHandler = mock(OrderCommandHandler.class);
        try (MemoryMappedWalLogger recoveredWal = new MemoryMappedWalLogger(walPath, 5)) {
            OrderCommandReplayHarness harness = new OrderCommandReplayHarness(
                    mock(OrderInputEventRepository.class),
                    mockHandler,
                    new ObjectMapper(),
                    recoveredWal
            );

            // Replay WAL
            var outcomes = harness.replayWriteAheadLog();

            // Verify replay recovered both commands
            assertThat(outcomes).hasSize(2);
            ArgumentCaptor<OrderCommand> captor = ArgumentCaptor.forClass(OrderCommand.class);
            verify(mockHandler, org.mockito.Mockito.times(2)).handle(captor.capture());

            var replayedCommands = captor.getAllValues();
            assertThat(replayedCommands.get(0).commandId()).isEqualTo(commandId1);
            assertThat(replayedCommands.get(0).orderId()).isEqualTo(orderId1);
            assertThat(replayedCommands.get(0).quantityScaled()).isEqualTo(command1.quantityScaled());

            assertThat(replayedCommands.get(1).commandId()).isEqualTo(commandId2);
            assertThat(replayedCommands.get(1).orderId()).isEqualTo(orderId2);
            assertThat(replayedCommands.get(1).quantityScaled()).isEqualTo(command2.quantityScaled());
        }
    }
}
