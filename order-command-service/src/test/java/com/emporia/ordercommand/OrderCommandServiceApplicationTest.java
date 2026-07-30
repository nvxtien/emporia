package com.emporia.ordercommand;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class OrderCommandServiceApplicationTest {
    @Test
    void applicationClassInstantiation() {
        assertThatCode(OrderCommandServiceApplication::new).doesNotThrowAnyException();
    }
}
