package com.emporia.ordermanagement;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderManagementServiceApplicationTest {

    @Test
    void instantiatesMainClass() {
        OrderManagementServiceApplication app = new OrderManagementServiceApplication();
        assertThat(app).isNotNull();
    }
}
