package com.emporia.execution;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class RestClientConfigTest {
    @Test
    void createsRestClientBuilder() {
        RestClientConfig config = new RestClientConfig();
        RestClient.Builder builder = config.restClientBuilder();
        assertThat(builder).isNotNull();
    }
}
