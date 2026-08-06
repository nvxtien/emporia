package com.emporia.portfolio;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortfolioAdminAuditServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PortfolioAdminAuditService service = new PortfolioAdminAuditService(jdbc, objectMapper);

    @Test
    void recordExecutesInsertQuery() {
        AdminAuditContext context = new AdminAuditContext("sub-1", "user-1", "desk-1", "req-1");
        service.record(context, "PORTFOLIO_PROVISIONED", "PORTFOLIO", "100", null, "after", null);

        verify(jdbc).update(anyString(), any(), any(), eq("sub-1"), eq("user-1"), eq("desk-1"), eq("PORTFOLIO_PROVISIONED"), eq("PORTFOLIO"), eq("100"), eq("SUCCESS"), eq("req-1"), eq(null), eq("\"after\""), eq(null));
    }

    @Test
    void listQueriesDatabaseAndReturnsPage() {
        UUID id = UUID.randomUUID();
        PortfolioAdminAuditService.AdminAuditView view = new PortfolioAdminAuditService.AdminAuditView(
                id, Instant.now(), "sub-1", "user-1", "desk-1", "PORTFOLIO_PROVISIONED", "PORTFOLIO", "100", "SUCCESS", "req-1", null, "{}", null
        );

        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(view));

        PortfolioAdminAuditService.AdminAuditFilter filter = new PortfolioAdminAuditService.AdminAuditFilter(
                "user", "PORTFOLIO_PROVISIONED", "PORTFOLIO", "100", "SUCCESS", 0, 10
        );

        PortfolioAdminAuditService.AdminAuditPage page = service.list(filter);
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().action()).isEqualTo("PORTFOLIO_PROVISIONED");
    }

    @Test
    void handlesNullFilterAndEmptyResults() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        PortfolioAdminAuditService.AdminAuditPage page = service.list(null);
        assertThat(page.totalElements()).isEqualTo(0);
        assertThat(page.items()).isEmpty();
    }
}

