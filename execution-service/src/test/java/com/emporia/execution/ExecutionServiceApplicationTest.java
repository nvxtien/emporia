package com.emporia.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class ExecutionServiceApplicationTest {
    @Test
    void applicationClassInstantiation() {
        assertThatCode(ExecutionServiceApplication::new).doesNotThrowAnyException();
    }
}
