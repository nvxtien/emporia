package com.emporia.execution;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionTopicsConfigTest {
    @Test
    void configCreatesTaskScheduler() {
        ExecutionTopicsConfig config = new ExecutionTopicsConfig();
        TaskScheduler scheduler = config.executionTaskScheduler();
        assertThat(scheduler).isNotNull();
    }
}
