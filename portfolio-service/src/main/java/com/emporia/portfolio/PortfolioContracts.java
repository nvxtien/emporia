package com.emporia.portfolio;

import java.util.List;

public final class PortfolioContracts {

    public static final int SCHEMA_VERSION = 1;

    private PortfolioContracts() {
    }

    public record Balance(
            Integer assetId,
            Long amount) {
    }

    public record RiskSeed(
            int schemaVersion,
            long clientId,
            long firstTransactionId,
            List<Balance> balances) {
    }

    public record Snapshot(
            Integer schemaVersion,
            String exchangeId,
            Long deliveryId,
            Long clientId,
            List<Balance> availableBalances) {
    }
}
