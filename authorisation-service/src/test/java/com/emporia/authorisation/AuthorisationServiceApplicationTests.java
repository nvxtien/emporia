package com.emporia.authorisation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:authorisation;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "emporia.auth.bootstrap-admin.enabled=false"
})
class AuthorisationServiceApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Test
    void exposesHealthAndOpenIdConfiguration() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> health = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString()
        );
        HttpResponse<String> openIdConfiguration = client.send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/.well-known/openid-configuration"
                )).build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
        assertThat(openIdConfiguration.statusCode()).isEqualTo(200);
        assertThat(openIdConfiguration.body()).contains("\"issuer\":\"http://localhost:3000\"");
    }

    @Test
    void registersBrowserClientAsPublicPkceClient() {
        RegisteredClient client = registeredClientRepository.findByClientId("emporia-web");

        assertThat(client).isNotNull();
        assertThat(client.getClientSecret()).isNull();
        assertThat(client.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(client.getAuthorizationGrantTypes()).containsExactly(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(client.getRedirectUris()).containsExactly("http://localhost:3000/auth/callback");
        assertThat(client.getPostLogoutRedirectUris())
                .containsExactly("http://localhost:3000/auth/logout-callback");
        assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isFalse();
    }

    @Test
    void registersMarketDataAsAConfidentialServiceClient() {
        RegisteredClient client = registeredClientRepository.findByClientId("emporia-market-data");

        assertThat(client).isNotNull();
        assertThat(client.getClientSecret()).isNotBlank();
        assertThat(client.getClientAuthenticationMethods())
                .containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(client.getAuthorizationGrantTypes()).containsExactly(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(client.getScopes()).containsExactly("internal");
        assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isFalse();
    }

    @Test
    void registersExecutionAsAConfidentialServiceClient() {
        RegisteredClient client = registeredClientRepository.findByClientId("emporia-execution");

        assertThat(client).isNotNull();
        assertThat(client.getClientSecret()).isNotBlank();
        assertThat(client.getClientAuthenticationMethods())
                .containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(client.getAuthorizationGrantTypes()).containsExactly(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(client.getScopes()).containsExactly("internal");
        assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isFalse();
    }

    @Test
    void issuesAnInternalClientCredentialsTokenToMarketData() throws Exception {
        String credentials = Base64.getEncoder().encodeToString(
                "emporia-market-data:emporia-market-data-local-secret"
                        .getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/oauth2/token"))
                .header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=client_credentials&scope=internal"))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"access_token\":")
                .contains("\"token_type\":\"Bearer\"")
                .contains("\"scope\":\"internal\"");
    }
}
