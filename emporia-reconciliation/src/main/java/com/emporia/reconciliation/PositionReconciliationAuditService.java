package com.emporia.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Position-Level Reconciliation Audit Service for Exchange-Core & Ledger Integration.
 *
 * <p>Audits share holdings (e.g., AAPL, TSLA stock quantities) by cross-verifying Exchange-Core in-memory
 * risk engine positions against PostgreSQL ledger position records (user_portfolio_positions).
 */
@Service
public class PositionReconciliationAuditService {
    private static final Logger log = LoggerFactory.getLogger(PositionReconciliationAuditService.class);

    public record UserInstrumentKey(long userId, String symbol) {}

    public record PositionAuditReport(
            long totalPositionsAudited,
            long matchedPositions,
            long discrepancyCount,
            List<String> discrepancyDetails,
            boolean isConsistent
    ) {}

    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong positionDiscrepancyCounter = new AtomicLong(0L);

    public PositionReconciliationAuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Executes a full position-level holdings reconciliation check against the PostgreSQL ledger.
     */
    public PositionAuditReport performPositionAudit(Map<UserInstrumentKey, BigDecimal> inMemoryPositions) {
        if (inMemoryPositions == null || inMemoryPositions.isEmpty()) {
            return new PositionAuditReport(0L, 0L, 0L, List.of(), true);
        }

        log.info("[Position Reconciliation] Starting position holdings audit for {} positions...", inMemoryPositions.size());

        long totalAudited = inMemoryPositions.size();
        long matched = 0;
        long discrepancies = 0;
        List<String> details = new ArrayList<>();

        for (Map.Entry<UserInstrumentKey, BigDecimal> entry : inMemoryPositions.entrySet()) {
            UserInstrumentKey key = entry.getKey();
            BigDecimal engineQty = entry.getValue() != null ? entry.getValue() : BigDecimal.ZERO;

            BigDecimal dbQty = fetchDbLedgerPositionQuantity(key.userId(), key.symbol());

            if (engineQty.compareTo(dbQty) == 0) {
                matched++;
            } else {
                discrepancies++;
                positionDiscrepancyCounter.incrementAndGet();
                String err = String.format("User %d symbol %s position mismatch: Engine=%s, DB=%s",
                        key.userId(), key.symbol(), engineQty, dbQty);
                log.error("[Position Discrepancy] {}", err);
                details.add(err);
            }
        }

        boolean consistent = (discrepancies == 0);
        log.info("[Position Reconciliation] Position audit complete. Matched: {}/{}, Discrepancies: {}",
                matched, totalAudited, discrepancies);

        return new PositionAuditReport(totalAudited, matched, discrepancies, List.copyOf(details), consistent);
    }

    private BigDecimal fetchDbLedgerPositionQuantity(long userId, String symbol) {
        try {
            String sql = "SELECT quantity FROM user_portfolio_positions WHERE user_id = ? AND symbol = ?";
            List<BigDecimal> results = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getBigDecimal("quantity"), userId, symbol);
            return results.isEmpty() ? BigDecimal.ZERO : results.get(0);
        } catch (Exception e) {
            log.debug("[Position Audit] DB position query skipped: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    public long getTotalDiscrepanciesDetected() {
        return positionDiscrepancyCounter.get();
    }
}
