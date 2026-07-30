package com.emporia.ordermanagement;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTopicsConfigTest {

    @Test
    void instantiatesKafkaTopicBeans() {
        KafkaTopicsConfig config = new KafkaTopicsConfig();

        NewTopic commands = config.orderCommandsTopic("emporia.order.commands.v1");
        assertThat(commands.name()).isEqualTo("emporia.order.commands.v1");
        assertThat(commands.numPartitions()).isEqualTo(6);

        NewTopic results = config.orderResultsTopic("emporia.order.results.v1");
        assertThat(results.name()).isEqualTo("emporia.order.results.v1");

        NewTopic orders = config.ordersTopic("emporia.orders.v1");
        assertThat(orders.name()).isEqualTo("emporia.orders.v1");

        NewTopic executions = config.executionCommandsTopic("emporia.execution.commands.v1");
        assertThat(executions.name()).isEqualTo("emporia.execution.commands.v1");
    }
}
