package com.emporia.authentication;

import com.emporia.authentication.user.UserAccount;
import com.emporia.authentication.user.UserAccountRepository;
import com.emporia.authentication.user.UserAuthority;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:authentication;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.hikari.schema=public",
        "spring.flyway.default-schema=public",
        "spring.flyway.schemas=public",
        "spring.jpa.properties.hibernate.default_schema=public",
        "spring.jpa.hibernate.ddl-auto=validate",
        "emporia.auth.bootstrap-admin.enabled=false"
})
class AuthenticationApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        userAccountRepository.deleteAll();
    }

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

    @Test
    void exposesAdminUserManagementToAdministrators() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String adminUsername = "admin-" + suffix;
        saveUser(adminUsername, adminUsername + "@example.test", "Admin12345!", "ops", true,
                Set.of(UserAuthority.ROLE_USER, UserAuthority.ROLE_ADMIN));

        HttpClient client = loggedInClient(adminUsername, "Admin12345!");

        HttpResponse<String> list = send(client, "/admin/users", "GET", null);

        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains(adminUsername);

        String managedUsername = "managed-" + suffix;
        HttpResponse<String> created = send(client, "/admin/users", "POST", """
                {
                  "username": "%s",
                  "email": "%s@example.test",
                  "password": "Managed12345!",
                  "desk": "client-a",
                  "canTrade": false,
                  "authorities": ["ROLE_USER"]
                }
                """.formatted(managedUsername, managedUsername));

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body())
                .contains("\"username\":\"" + managedUsername + "\"")
                .contains("\"canTrade\":false")
                .doesNotContain("Managed12345!");

        UserAccount managed = userAccountRepository.findByUsernameIgnoreCase(managedUsername).orElseThrow();
        HttpResponse<String> tradingIdentity = send(client,
                "/admin/users/" + managed.getId() + "/trading-identity", "PUT", """
                        {
                          "desk": "client-b",
                          "canTrade": true
                        }
                        """);

        assertThat(tradingIdentity.statusCode()).isEqualTo(200);
        assertThat(tradingIdentity.body())
                .contains("\"desk\":\"client-b\"")
                .contains("\"canTrade\":true");

        HttpResponse<String> password = send(client,
                "/admin/users/" + managed.getId() + "/password", "PUT", """
                        {
                          "password": "Managed67890!"
                        }
                        """);

        assertThat(password.statusCode()).isEqualTo(200);
        assertThat(password.body()).doesNotContain("Managed67890!");

        HttpResponse<String> audit = send(client,
                "/admin/audit/events?entityId=" + managed.getId(), "GET", null);

        assertThat(audit.statusCode()).isEqualTo(200);
        assertThat(audit.body())
                .contains("\"actorUsername\":\"" + adminUsername + "\"")
                .contains("\"entityId\":\"" + managed.getId() + "\"")
                .contains("USER_CREATED")
                .contains("USER_TRADING_IDENTITY_UPDATED")
                .contains("USER_PASSWORD_CHANGED")
                .doesNotContain("Managed12345!")
                .doesNotContain("Managed67890!")
                .doesNotContain("passwordHash");
    }

    @Test
    void updatesUserTierViaAdminApi() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String adminUsername = "admin-" + suffix;
        saveUser(adminUsername, adminUsername + "@example.test", "Admin12345!", "ops", true,
                Set.of(UserAuthority.ROLE_USER, UserAuthority.ROLE_ADMIN));

        HttpClient client = loggedInClient(adminUsername, "Admin12345!");

        String targetUsername = "institutional-user-" + suffix;
        saveUser(targetUsername, targetUsername + "@example.test", "Trader12345!", "desk-hft", true,
                Set.of(UserAuthority.ROLE_USER));

        UserAccount account = userAccountRepository.findByUsernameIgnoreCase(targetUsername).orElseThrow();
        assertThat(account.getTier()).isEqualTo(com.emporia.authentication.user.UserTier.RETAIL);

        HttpResponse<String> response = send(client, "/admin/users/" + account.getId() + "/tier", "PUT", """
                {
                  "tier": "INSTITUTIONAL"
                }
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"tier\":\"INSTITUTIONAL\"");

        UserAccount updated = userAccountRepository.findByUsernameIgnoreCase(targetUsername).orElseThrow();
        assertThat(updated.getTier()).isEqualTo(com.emporia.authentication.user.UserTier.INSTITUTIONAL);
    }

    @Test
    void rejectsAdminUserManagementForNonAdministrators() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String username = "viewer-" + suffix;
        saveUser(username, username + "@example.test", "Viewer12345!", "read-only", false,
                Set.of(UserAuthority.ROLE_USER));

        HttpClient client = loggedInClient(username, "Viewer12345!");
        HttpResponse<String> response = send(client, "/admin/users", "GET", null);
        HttpResponse<String> audit = send(client, "/admin/audit/events", "GET", null);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(audit.statusCode()).isEqualTo(403);
    }

    @Test
    void rejectsDisablingTheLastEnabledAdministratorWithProblemDetail() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String adminUsername = "admin-" + suffix;
        saveUser(adminUsername, adminUsername + "@example.test", "Admin12345!", "ops", true,
                Set.of(UserAuthority.ROLE_USER, UserAuthority.ROLE_ADMIN));
        saveUser("viewer-" + suffix, "viewer-" + suffix + "@example.test", "Viewer12345!", "read-only", false,
                Set.of(UserAuthority.ROLE_USER));

        HttpClient client = loggedInClient(adminUsername, "Admin12345!");
        UserAccount admin = userAccountRepository.findByUsernameIgnoreCase(adminUsername).orElseThrow();

        HttpResponse<String> response = send(client, "/admin/users/" + admin.getId(), "PUT", """
                {
                  "username": "%s",
                  "email": "%s@example.test",
                  "desk": "ops",
                  "canTrade": true,
                  "enabled": false,
                  "authorities": ["ROLE_USER", "ROLE_ADMIN"]
                }
                """.formatted(adminUsername, adminUsername));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("\"detail\":\"At least one enabled administrator is required\"");
    }

    @Test
    void redirectsDisabledUsersToAccountDisabledLoginMessage() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String username = "disabled-" + suffix;
        saveUser(username, username + "@example.test", "Disabled12345!", "suspended", false, false,
                Set.of(UserAuthority.ROLE_USER));

        HttpClient client = browserClient();
        HttpResponse<String> login = login(client, username, "Disabled12345!");

        assertThat(login.statusCode()).isIn(302, 303);
        assertThat(login.headers().firstValue("Location").orElse("")).contains("/login?disabled");

        HttpResponse<String> disabledLoginPage = send(client, "/login?disabled", "GET", null);
        assertThat(disabledLoginPage.statusCode()).isEqualTo(200);
        assertThat(disabledLoginPage.body())
                .contains("Your account is disabled. Contact an administrator before signing in again.")
                .doesNotContain("Invalid username or password. Please try again.");
    }

    @Test
    void keepsInvalidCredentialsOnGenericLoginMessage() throws Exception {
        HttpClient client = browserClient();
        HttpResponse<String> login = login(client, "missing-user", "Wrong12345!");

        assertThat(login.statusCode()).isIn(302, 303);
        assertThat(login.headers().firstValue("Location").orElse("")).contains("/login?error");

        HttpResponse<String> invalidLoginPage = send(client, "/login?error", "GET", null);
        assertThat(invalidLoginPage.statusCode()).isEqualTo(200);
        assertThat(invalidLoginPage.body())
                .contains("Invalid username or password. Please try again.")
                .doesNotContain("Your account is disabled.");
    }

    private void saveUser(
            String username,
            String email,
            String password,
            String desk,
            boolean canTrade,
            Set<UserAuthority> authorities
    ) {
        saveUser(username, email, password, desk, canTrade, true, authorities);
    }

    private void saveUser(
            String username,
            String email,
            String password,
            String desk,
            boolean canTrade,
            boolean enabled,
            Set<UserAuthority> authorities
    ) {
        UserAccount account = new UserAccount(
                username,
                email,
                passwordEncoder.encode(password),
                desk,
                canTrade,
                authorities
        );
        if (!enabled) {
            account.updateAccount(username, email, false, authorities);
        }
        userAccountRepository.save(account);
    }

    private HttpClient browserClient() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private HttpClient loggedInClient(String username, String password) throws Exception {
        HttpClient client = browserClient();
        HttpResponse<String> login = login(client, username, password);

        assertThat(login.statusCode()).isIn(302, 303);
        assertThat(login.headers().firstValue("Location").orElse(""))
                .doesNotContain("error")
                .doesNotContain("disabled");
        return client;
    }

    private HttpResponse<String> login(HttpClient client, String username, String password) throws Exception {
        HttpResponse<String> csrf = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/csrf")).build(),
                HttpResponse.BodyHandlers.ofString()
        );
        String parameterName = jsonValue(csrf.body(), "parameterName");
        String token = jsonValue(csrf.body(), "token");
        String body = form("username", username)
                + "&" + form("password", password)
                + "&" + form(parameterName, token);

        HttpResponse<String> login = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        return login;
    }

    private HttpResponse<String> send(HttpClient client, String path, String method, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String form(String name, String value) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String jsonValue(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int start = json.indexOf(needle);
        assertThat(start).isGreaterThanOrEqualTo(0);
        int valueStart = start + needle.length();
        int valueEnd = json.indexOf('"', valueStart);
        assertThat(valueEnd).isGreaterThan(valueStart);
        return json.substring(valueStart, valueEnd);
    }
}
