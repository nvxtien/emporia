package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.ordermanagement.model.OrderInputEvent;
import com.emporia.ordermanagement.repository.OrderInputEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCommandReplayHarnessTest {
    @Test
    void replaysCommandsInSequenceOrder() throws Exception {
        OrderInputEventRepository inputEvents = mock(OrderInputEventRepository.class);
        OrderCommandHandler handler = mock(OrderCommandHandler.class);
        ObjectMapper objectMapper = new ObjectMapper();
        OrderCommandReplayHarness harness = new OrderCommandReplayHarness(inputEvents, handler, objectMapper, new MemoryMappedWalLogger(null, 1));

        OrderCommand first = TestCommands.command(UUID.randomUUID());
        OrderCommand second = TestCommands.command(UUID.randomUUID());
        OrderInputEvent firstEvent = new OrderInputEvent(first, objectMapper.writeValueAsString(first));
        OrderInputEvent secondEvent = new OrderInputEvent(second, objectMapper.writeValueAsString(second));
        ReflectionTestUtils.setField(firstEvent, "sequenceId", 1L);
        ReflectionTestUtils.setField(secondEvent, "sequenceId", 2L);

        when(inputEvents.findAllByOrderBySequenceIdAsc()).thenReturn(List.of(secondEvent, firstEvent));
        when(handler.handle(first)).thenReturn(TestCommands.outcome(first.commandId()));
        when(handler.handle(second)).thenReturn(TestCommands.outcome(second.commandId()));

        assertThat(harness.replayAll())
                .extracting(outcome -> outcome.result().commandId())
                .containsExactly(first.commandId(), second.commandId());
    }

    @Test
    void replaysTheCommandsTheWriteAheadLogStillHolds() throws Exception {
        String path = java.nio.file.Files.createTempDirectory("wal-replay")
                .resolve("replay.log").toString();
        OrderCommand pending = TestCommands.command(UUID.randomUUID());
        try (MemoryMappedWalLogger writer = new MemoryMappedWalLogger(path, 1)) {
            writer.append(com.emporia.events.sbe.SbeEncoderDecoder.encodeOrderCommand(pending));
        }

        OrderCommandHandler handler = mock(OrderCommandHandler.class);
        try (MemoryMappedWalLogger recovered = new MemoryMappedWalLogger(path, 1)) {
            OrderCommandReplayHarness harness = new OrderCommandReplayHarness(
                    mock(OrderInputEventRepository.class), handler, new ObjectMapper(), recovered);

            harness.replayWriteAheadLog();
        }

        // The order was accepted on the ring but not yet written when the
        // process stopped, so the database has no record of it and the log is
        // the only place it survives.
        //
        // Matched on identity rather than the whole record: fixed-point
        // encoding normalises decimals to scale 6, so "0.01" comes back as
        // "0.010000" - the same quantity, a different BigDecimal.
        org.mockito.ArgumentCaptor<OrderCommand> replayed =
                org.mockito.ArgumentCaptor.forClass(OrderCommand.class);
        verify(handler).handle(replayed.capture());
        assertThat(replayed.getValue().commandId()).isEqualTo(pending.commandId());
        assertThat(replayed.getValue().orderId()).isEqualTo(pending.orderId());
        assertThat(replayed.getValue().requestedAt()).isEqualTo(pending.requestedAt());
        assertThat(replayed.getValue().quantity())
                .isEqualByComparingTo(pending.quantity());
    }

    @Test
    void doesNothingWhenNoLogIsConfigured() {
        OrderCommandHandler handler = mock(OrderCommandHandler.class);
        OrderCommandReplayHarness harness = new OrderCommandReplayHarness(
                mock(OrderInputEventRepository.class), handler, new ObjectMapper(),
                new MemoryMappedWalLogger(null, 1));

        assertThat(harness.replayWriteAheadLog()).isEmpty();
        verifyNoInteractions(handler);
    }
}
