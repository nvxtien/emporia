package com.emporia.portfolio;

import com.emporia.portfolio.PortfolioContracts.RiskSeed;

import java.time.Instant;

interface PortfolioStore {

    RiskSeed load(long clientId);

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
