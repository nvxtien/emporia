package com.emporia.portfolio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class PortfolioServiceApplicationTest {
    @Test
    void applicationClassInstantiation() {
        assertThatCode(PortfolioServiceApplication::new).doesNotThrowAnyException();
    }
}
