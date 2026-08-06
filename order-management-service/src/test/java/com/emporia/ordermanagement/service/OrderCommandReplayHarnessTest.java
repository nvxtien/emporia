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
import static org.mockito.Mockito.when;

class OrderCommandReplayHarnessTest {
    @Test
    void replaysCommandsInSequenceOrder() throws Exception {
        OrderInputEventRepository inputEvents = mock(OrderInputEventRepository.class);
        OrderCommandHandler handler = mock(OrderCommandHandler.class);
        ObjectMapper objectMapper = new ObjectMapper();
        OrderCommandReplayHarness harness = new OrderCommandReplayHarness(inputEvents, handler, objectMapper);

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
}