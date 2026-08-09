package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.ordermanagement.model.OrderInputEvent;
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
        try {
            asyncDbWriter.enqueue(new OrderInputEvent(command, objectMapper.writeValueAsString(command)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not record order command input " + command.commandId(), exception);
        }
    }
}
