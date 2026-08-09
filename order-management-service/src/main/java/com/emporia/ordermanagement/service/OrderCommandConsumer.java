package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.ordermanagement.disruptor.DisruptorOrderPipeline;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderCommandConsumer {
    private final DisruptorOrderPipeline disruptorPipeline;
    private final OrderInputEventRecorder inputRecorder;

    public OrderCommandConsumer(DisruptorOrderPipeline disruptorPipeline, OrderInputEventRecorder inputRecorder) {
        this.disruptorPipeline = disruptorPipeline;
        this.inputRecorder = inputRecorder;
    }

    // Keep this consumer identity stable across the service rename. Changing it would make
    // Kafka treat the deployment as a new consumer group and replay retained commands.
    //
    // Hands off to the same single-writer ring the REST intake path uses, rather than
    // calling OrderCommandHandler or Kafka directly - this is the only route SMART/VWAP
    // strategy child orders take (see ExecutionEventConsumer.publishChild in
    // execution-service), so it needs the same WAL and outbox durability REST orders get.
    @KafkaListener(topics = "${emporia.kafka.commands-topic}", groupId = "order-data-service-v1")
    public void consume(OrderCommand command) {
        inputRecorder.record(command);
        disruptorPipeline.submit(command).join();
    }
}
