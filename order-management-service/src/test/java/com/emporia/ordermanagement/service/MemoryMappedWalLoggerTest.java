package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderStatus;
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
    void disabledWhenNoPathConfigured() {
        try (MemoryMappedWalLogger logger = new MemoryMappedWalLogger(null, 1)) {
            assertThat(logger.isEnabled()).isFalse();
            assertThat(logger.append(new byte[]{1, 2, 3})).isFalse();
        }
    }
}
