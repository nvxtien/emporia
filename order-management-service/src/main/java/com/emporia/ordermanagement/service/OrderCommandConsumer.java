package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.ordermanagement.disruptor.DisruptorOrderPipeline;
import com.emporia.ordermanagement.model.OrderInputEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class OrderCommandConsumer {
    private final DisruptorOrderPipeline disruptorPipeline;
    private final AsyncDbWriter asyncDbWriter;
    private final ObjectMapper objectMapper;

    public OrderCommandConsumer(DisruptorOrderPipeline disruptorPipeline, AsyncDbWriter asyncDbWriter,
                                ObjectMapper objectMapper) {
        this.disruptorPipeline = disruptorPipeline;
        this.asyncDbWriter = asyncDbWriter;
        this.objectMapper = objectMapper;
    }

    // Keep this consumer identity stable across the service rename. Changing it would make
    // Kafka treat the deployment as a new consumer group and replay retained commands.
    //
    // Hands off to the same single-writer ring the REST intake path uses, rather than
    // calling OrderCommandHandler or Kafka directly - this is the only route SMART/VWAP
    // strategy child orders take (see ExecutionEventConsumer.publishChild in
    // execution-service), so it needs the same WAL and outbox durability REST orders get.
    @KafkaListener(topics = "${emporia.kafka.commands-topic}", groupId = "order-data-service-v1")
    public void consume(OrderCommand command) throws Exception {
        asyncDbWriter.enqueue(new OrderInputEvent(command, objectMapper.writeValueAsString(command)));
        disruptorPipeline.submit(command).join();
    }
}
