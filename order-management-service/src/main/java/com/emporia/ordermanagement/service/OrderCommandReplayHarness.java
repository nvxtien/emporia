package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
import com.emporia.ordermanagement.model.OrderInputEvent;
import com.emporia.ordermanagement.repository.OrderInputEventRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Comparator;

@Service
class OrderCommandReplayHarness {
    private final OrderInputEventRepository inputEvents;
    private final OrderCommandHandler handler;
    private final ObjectMapper objectMapper;

    OrderCommandReplayHarness(OrderInputEventRepository inputEvents, OrderCommandHandler handler,
                              ObjectMapper objectMapper) {
        this.inputEvents = inputEvents;
        this.handler = handler;
        this.objectMapper = objectMapper;
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