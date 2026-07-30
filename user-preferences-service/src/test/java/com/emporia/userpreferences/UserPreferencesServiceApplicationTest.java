package com.emporia.userpreferences;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class UserPreferencesServiceApplicationTest {
    @Test
    void applicationClassInstantiation() {
        assertThatCode(UserPreferencesServiceApplication::new).doesNotThrowAnyException();
    }
}
