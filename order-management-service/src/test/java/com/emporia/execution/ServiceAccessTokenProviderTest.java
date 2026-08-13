package com.emporia.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceAccessTokenProviderTest {
    private MockRestServiceServer server;
    private ServiceAccessTokenProvider provider;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://auth");
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new ServiceAccessTokenProvider(builder.build(), "client-id", "client-secret");
    }

    @Test
    void authorizationFetchesAndCachesToken() throws Exception {
        Map<String, Object> tokenResponse = Map.of("access_token", "jwt-token-123", "expires_in", 3600);

        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://auth"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(mapper.writeValueAsString(tokenResponse), MediaType.APPLICATION_JSON));

        String header1 = provider.authorization();
        assertThat(header1).isEqualTo("Bearer jwt-token-123");

        // Second call should return cached token without making another HTTP request
        String header2 = provider.authorization();
        assertThat(header2).isEqualTo("Bearer jwt-token-123");
        server.verify();
    }

    @Test
    void authorizationWithNoExpiresInUsesDefaultTtl() throws Exception {
        // expires_in is missing — should use default of 600 and still return the token
        Map<String, Object> tokenResponse = Map.of("access_token", "jwt-no-expiry");

        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://auth"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(mapper.writeValueAsString(tokenResponse), MediaType.APPLICATION_JSON));

        String header = provider.authorization();
        assertThat(header).isEqualTo("Bearer jwt-no-expiry");
        server.verify();
    }

    @Test
    void authorizationThrowsWhenTokenResponseMissingAccessToken() throws Exception {
        Map<String, Object> badResponse = Map.of("error", "invalid_client");

        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://auth"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(mapper.writeValueAsString(badResponse), MediaType.APPLICATION_JSON));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.authorization())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no execution access token");
        server.verify();
    }
}
