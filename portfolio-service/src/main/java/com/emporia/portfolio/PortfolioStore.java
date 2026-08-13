package com.emporia.portfolio;

import com.emporia.portfolio.PortfolioContracts.Balance;
import com.emporia.portfolio.PortfolioContracts.PortfolioState;
import com.emporia.portfolio.PortfolioContracts.RiskSeed;

import java.time.Instant;
import java.util.List;

interface PortfolioStore {

    RiskSeed load(long clientId);

    PortfolioState state(long clientId);

    boolean exists(long clientId);

    PortfolioState provision(
            long clientId,
            long firstTransactionId,
            List<Balance> balances,
            Instant updatedAt);

    void lockClient(long clientId);

    /**
     * The highest delivery id already applied for this (clientId, exchangeId)
     * pair, or -1 if none has been applied yet. Callers use this to reject a
     * snapshot older than what is already in place, since replaceBalances
     * itself has no ordering guarantee against the delivery transport.
     */
    long lastDeliveryId(long clientId, String exchangeId);

    PortfolioReceipt findReceipt(String eventId);

    void recordReceipt(
            String eventId,
            String payloadSha256,
            byte[] payload,
            ValidatedPortfolioSnapshot snapshot,
            Instant receivedAt);

    void replaceBalances(
            ValidatedPortfolioSnapshot snapshot,
            Instant updatedAt);
}
