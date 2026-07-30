package com.emporia.execution;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionTopicsConfigTest {
    @Test
    void configCreatesBeans() {
        ExecutionTopicsConfig config = new ExecutionTopicsConfig();
        NewTopic topic = config.executionCommandsTopic("emporia.execution.commands.v1");
        assertThat(topic.name()).isEqualTo("emporia.execution.commands.v1");
        assertThat(topic.numPartitions()).isEqualTo(6);

        TaskScheduler scheduler = config.executionTaskScheduler();
        assertThat(scheduler).isNotNull();
    }
}
