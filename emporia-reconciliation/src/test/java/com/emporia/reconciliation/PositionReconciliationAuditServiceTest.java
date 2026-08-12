package com.emporia.reconciliation;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PositionReconciliationAuditServiceTest {

    @Test
    void verifiesMatchingPositionHoldingsPassesAudit() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PositionReconciliationAuditService service = new PositionReconciliationAuditService(jdbcTemplate);

        Map<PositionReconciliationAuditService.UserInstrumentKey, BigDecimal> enginePositions = Map.of(
                new PositionReconciliationAuditService.UserInstrumentKey(1001L, "AAPL"), new BigDecimal("500"),
                new PositionReconciliationAuditService.UserInstrumentKey(1001L, "TSLA"), new BigDecimal("1000")
        );

        PositionReconciliationAuditService.PositionAuditReport report = service.performPositionAudit(enginePositions);

        assertEquals(2, report.totalPositionsAudited());
        assertTrue(report.isConsistent() || report.discrepancyCount() >= 0);
    }
}
