package com.emporia.execution.warmup;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class JvmWarmupRunnerTest {

    @Test
    void runsWarmupIterationsWithoutError() {
        JvmWarmupRunner runner = new JvmWarmupRunner(true, 500);
        assertDoesNotThrow(() -> runner.run(new DefaultApplicationArguments()));
    }

    @Test
    void skipsWarmupWhenDisabled() {
        JvmWarmupRunner runner = new JvmWarmupRunner(false, 500);
        assertDoesNotThrow(() -> runner.run(new DefaultApplicationArguments()));
    }
}
