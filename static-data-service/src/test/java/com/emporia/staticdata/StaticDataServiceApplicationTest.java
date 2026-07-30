package com.emporia.staticdata;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class StaticDataServiceApplicationTest {
    @Test
    void applicationClassInstantiation() {
        assertThatCode(StaticDataServiceApplication::new).doesNotThrowAnyException();
    }
}
