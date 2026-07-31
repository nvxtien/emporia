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
