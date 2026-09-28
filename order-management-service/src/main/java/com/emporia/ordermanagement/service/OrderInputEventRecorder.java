package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.ordermanagement.model.OrderInputEvent;
import com.emporia.ordermanagement.model.OrderInputEventStage;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderInputEventRecorder {
    private final AsyncDbWriter asyncDbWriter;
    private final ObjectMapper objectMapper;

    public OrderInputEventRecorder(AsyncDbWriter asyncDbWriter, ObjectMapper objectMapper) {
        this.asyncDbWriter = asyncDbWriter;
        this.objectMapper = objectMapper;
    }

    public void record(OrderCommand command) {
        record(command, OrderInputEventStage.RECEIVED);
    }

    public void recordAccepted(OrderCommand command) {
        record(command, OrderInputEventStage.ACCEPTED);
    }

    public void recordApplied(OrderCommand command) {
        record(command, OrderInputEventStage.APPLIED);
    }

    private void record(OrderCommand command, OrderInputEventStage stage) {
        try {
            asyncDbWriter.enqueue(new OrderInputEvent(command, objectMapper.writeValueAsString(command), stage));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not record order command input " + command.commandId(), exception);
        }
    }
}
