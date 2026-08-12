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
 * Balance-Level Reconciliation Audit Service for Exchange-Core & Ledger Integration.
 *
 * <p>Audits financial integrity by cross-verifying Exchange-Core in-memory user cash/asset balances
 * against PostgreSQL ledger accounts. Detects balance drift, unauthorized overdrafts, or double-spending.
 */
@Service
public class BalanceReconciliationAuditService {
    private static final Logger log = LoggerFactory.getLogger(BalanceReconciliationAuditService.class);

    public record BalanceAuditReport(
            long totalAccountsAudited,
            long matchedAccounts,
            long discrepancyCount,
            List<String> discrepancyDetails,
            boolean isConsistent
    ) {}

    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong discrepancyCounter = new AtomicLong(0L);

    public BalanceReconciliationAuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Executes a full balance-level reconciliation check against the PostgreSQL ledger.
     */
    public BalanceAuditReport performBalanceAudit(Map<Long, BigDecimal> inMemoryAccountBalances) {
        log.info("[Balance Reconciliation] Starting balance audit for {} accounts...", inMemoryAccountBalances.size());

        long totalAudited = inMemoryAccountBalances.size();
        long matched = 0;
        long discrepancies = 0;
        List<String> details = new ArrayList<>();

        for (Map.Entry<Long, BigDecimal> entry : inMemoryAccountBalances.entrySet()) {
            Long userId = entry.getKey();
            BigDecimal engineBalance = entry.getValue();

            BigDecimal dbBalance = fetchDbLedgerBalance(userId);

            if (dbBalance != null && engineBalance.compareTo(dbBalance) == 0) {
                matched++;
            } else {
                discrepancies++;
                discrepancyCounter.incrementAndGet();
                String err = String.format("Account %d balance mismatch: Engine=%s, DB=%s",
                        userId, engineBalance, dbBalance);
                log.error("[Balance Discrepancy] {}", err);
                details.add(err);
            }
        }

        boolean consistent = (discrepancies == 0);
        log.info("[Balance Reconciliation] Audit complete. Matched: {}/{}, Discrepancies: {}",
                matched, totalAudited, discrepancies);

        return new BalanceAuditReport(totalAudited, matched, discrepancies, details, consistent);
    }

    private BigDecimal fetchDbLedgerBalance(Long userId) {
        try {
            String sql = "SELECT usd_balance FROM user_portfolio_accounts WHERE user_id = ?";
            List<BigDecimal> results = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getBigDecimal("usd_balance"), userId);
            return results.isEmpty() ? BigDecimal.ZERO : results.get(0);
        } catch (Exception e) {
            log.debug("[Balance Audit] DB ledger query skipped: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    public long getTotalDiscrepanciesDetected() {
        return discrepancyCounter.get();
    }
}
