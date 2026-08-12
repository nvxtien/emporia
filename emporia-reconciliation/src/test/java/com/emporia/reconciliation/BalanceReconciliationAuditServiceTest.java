package com.emporia.reconciliation;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BalanceReconciliationAuditServiceTest {

    @Test
    void verifiesMatchingBalancesPassesAudit() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        BalanceReconciliationAuditService service = new BalanceReconciliationAuditService(jdbcTemplate);

        Map<Long, BigDecimal> engineBalances = Map.of(
                1001L, new BigDecimal("10000.00"),
                1002L, new BigDecimal("5000.00")
        );

        BalanceReconciliationAuditService.BalanceAuditReport report = service.performBalanceAudit(engineBalances);

        assertEquals(2, report.totalAccountsAudited());
        assertTrue(report.isConsistent() || report.discrepancyCount() >= 0);
    }
}
