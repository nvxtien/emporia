package com.emporia.portfolio;

import com.emporia.portfolio.PortfolioContracts.Balance;
import com.emporia.portfolio.PortfolioContracts.Snapshot;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;

@Component
class PortfolioSnapshotValidator {

    ValidatedPortfolioSnapshot validate(
            final long pathDeliveryId,
            final long pathClientId,
            final String eventId,
            final Snapshot snapshot) {
        if (snapshot == null) {
            throw new PortfolioContractException(
                    "snapshot body is required");
        }
        if (snapshot.schemaVersion() == null
                || snapshot.schemaVersion()
                != PortfolioContracts.SCHEMA_VERSION) {
            throw new PortfolioContractException(
                    "unsupported snapshot schema version");
        }
        if (snapshot.exchangeId() == null
                || !snapshot.exchangeId()
                        .matches("[A-Za-z0-9._-]+")) {
            throw new PortfolioContractException(
                    "exchangeId is invalid");
        }
        if (snapshot.deliveryId() == null
                || snapshot.deliveryId() < 0
                || snapshot.deliveryId() != pathDeliveryId) {
            throw new PortfolioContractException(
                    "deliveryId does not match the request path");
        }
        if (snapshot.clientId() == null
                || snapshot.clientId() <= 0
                || snapshot.clientId() != pathClientId) {
            throw new PortfolioContractException(
                    "clientId does not match the request path");
        }
        final String expectedEventId =
                snapshot.exchangeId()
                        + ":"
                        + pathDeliveryId
                        + ":"
                        + pathClientId;
        if (!expectedEventId.equals(eventId)) {
            throw new PortfolioContractException(
                    "Idempotency-Key does not match the snapshot");
        }
        if (snapshot.availableBalances() == null) {
            throw new PortfolioContractException(
                    "availableBalances is required");
        }

        final Map<Integer, Long> balances = new TreeMap<>();
        for (final Balance balance : snapshot.availableBalances()) {
            if (balance == null
                    || balance.assetId() == null
                    || balance.assetId() < 0
                    || balance.amount() == null
                    || balance.amount() < 0) {
                throw new PortfolioContractException(
                        "balances require non-negative assetId and amount");
            }
            if (balances.put(
                    balance.assetId(),
                    balance.amount()) != null) {
                throw new PortfolioContractException(
                        "duplicate balance assetId "
                                + balance.assetId());
            }
        }
        return new ValidatedPortfolioSnapshot(
                snapshot.exchangeId(),
                pathDeliveryId,
                pathClientId,
                Map.copyOf(balances));
    }
}
