package com.emporia.portfolio;

import com.emporia.portfolio.PortfolioContracts.Balance;
import com.emporia.portfolio.PortfolioContracts.PortfolioState;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortfolioAdminControllerTest {

    private final PortfolioReceiptService service = mock(PortfolioReceiptService.class);
    private final PortfolioAdminAuditService audit = mock(PortfolioAdminAuditService.class);
    private final PortfolioAdminController controller = new PortfolioAdminController(service, audit);

    @Test
    void stateReturnsPortfolioStateForAdmins() {
        PortfolioState state = new PortfolioState(
                1,
                100L,
                50L,
                Instant.parse("2026-07-30T17:53:31.658Z"),
                List.of(new Balance(1, 1000L)),
                null);
        when(service.state(100L)).thenReturn(state);

        PortfolioState result = controller.state(adminJwt(), 100L);

        assertThat(result.clientId()).isEqualTo(100L);
        assertThat(result.balances()).hasSize(1);
    }

    @Test
    void stateRejectsNonAdmins() {
        assertThatThrownBy(() -> controller.state(userJwt(), 100L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Administrator access required");
    }

    @Test
    void provisionCreatesPortfolioForAdminsAndAuditsIt() {
        PortfolioState state = new PortfolioState(
                1,
                100L,
                50L,
                Instant.parse("2026-07-30T17:53:31.658Z"),
                List.of(new Balance(1, 1000L)),
                null);
        when(service.provision(100L, 50L, List.of(new Balance(1, 1000L))))
                .thenReturn(state);

        PortfolioState result = controller.provision(
                adminJwt(),
                100L,
                new ProvisionPortfolioRequest(50L, List.of(new Balance(1, 1000L))),
                "req-123");

        assertThat(result).isEqualTo(state);
        verify(audit).record(
                any(AdminAuditContext.class),
                eq("PORTFOLIO_PROVISIONED"),
                eq("PORTFOLIO"),
                eq("100"),
                eq(null),
                eq(state),
                eq(null));
    }

    @Test
    void provisionReturnsConflictWhenPortfolioAlreadyExists() {
        when(service.provision(100L, 50L, List.of()))
                .thenThrow(new PortfolioAlreadyExistsException(100L));

        assertThatThrownBy(() -> controller.provision(
                adminJwt(),
                100L,
                new ProvisionPortfolioRequest(50L, List.of()),
                null))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void auditEventsRequireAdminAndDelegateToAuditService() {
        PortfolioAdminAuditService.AdminAuditPage page =
                new PortfolioAdminAuditService.AdminAuditPage(
                        List.of(new PortfolioAdminAuditService.AdminAuditView(
                                UUID.randomUUID(),
                                Instant.parse("2026-07-30T17:53:31.658Z"),
                                "admin",
                                "admin",
                                "desk",
                                "PORTFOLIO_PROVISIONED",
                                "PORTFOLIO",
                                "100",
                                "SUCCESS",
                                "req-123",
                                null,
                                "{}",
                                null)),
                        0,
                        50,
                        1,
                        1,
                        true,
                        true);
        when(audit.list(any(PortfolioAdminAuditService.AdminAuditFilter.class))).thenReturn(page);

        PortfolioAdminAuditService.AdminAuditPage result =
                controller.auditEvents(adminJwt(), null, null, null, null, null, 0, 50);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().action()).isEqualTo("PORTFOLIO_PROVISIONED");
        assertThatThrownBy(() -> controller.auditEvents(userJwt(), null, null, null, null, null, 0, 50))
                .isInstanceOf(ResponseStatusException.class);
    }

    private static Jwt adminJwt() {
        return Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .subject("admin")
                .claim("authorities", List.of("ROLE_USER", "ROLE_ADMIN"))
                .build();
    }

    private static Jwt userJwt() {
        return Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .subject("user")
                .claim("authorities", List.of("ROLE_USER"))
                .build();
    }
}
