package com.emporia.portfolio;

import com.emporia.portfolio.PortfolioContracts.Balance;
import com.emporia.portfolio.PortfolioContracts.PortfolioState;
import com.emporia.portfolio.PortfolioContracts.RiskSeed;
import com.emporia.portfolio.PortfolioContracts.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Service
class PortfolioReceiptService {
    private static final int MAX_PROVISIONED_BALANCES = 500;

    private final PortfolioStore portfolios;
    private final PortfolioSnapshotValidator validator;
    private final Clock clock;

    @Autowired
    PortfolioReceiptService(
            final PortfolioStore portfolios,
            final PortfolioSnapshotValidator validator) {
        this(portfolios, validator, Clock.systemUTC());
    }

    PortfolioReceiptService(
            final PortfolioStore portfolios,
            final PortfolioSnapshotValidator validator,
            final Clock clock) {
        this.portfolios = portfolios;
        this.validator = validator;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    RiskSeed load(final long clientId) {
        if (clientId <= 0) {
            throw new PortfolioContractException(
                    "clientId must be positive");
        }
        return portfolios.load(clientId);
    }

    @Transactional(readOnly = true)
    PortfolioState state(final long clientId) {
        if (clientId <= 0) {
            throw new PortfolioContractException(
                    "clientId must be positive");
        }
        return portfolios.state(clientId);
    }

    @Transactional
    PortfolioState provision(
            final long clientId,
            final long firstTransactionId,
            final List<Balance> balances) {
        if (clientId <= 0) {
            throw new PortfolioContractException(
                    "clientId must be positive");
        }
        if (firstTransactionId <= 0) {
            throw new PortfolioContractException(
                    "firstTransactionId must be positive");
        }
        final List<Balance> validatedBalances =
                validateProvisionedBalances(balances);
        if (portfolios.exists(clientId)) {
            throw new PortfolioAlreadyExistsException(clientId);
        }
        return portfolios.provision(
                clientId,
                firstTransactionId,
                validatedBalances,
                clock.instant());
    }

    @Transactional
    ReceiptResult apply(
            final long pathDeliveryId,
            final long pathClientId,
            final String eventId,
            final byte[] payload,
            final Snapshot snapshot) {
        final ValidatedPortfolioSnapshot validated =
                validator.validate(
                        pathDeliveryId,
                        pathClientId,
                        eventId,
                        snapshot);
        final String digest = sha256(payload);

        portfolios.lockClient(pathClientId);
        final PortfolioReceipt existing =
                portfolios.findReceipt(eventId);
        if (existing != null) {
            if (digest.equals(existing.payloadSha256())
                    && Arrays.equals(payload, existing.payload())) {
                return ReceiptResult.DUPLICATE;
            }
            throw new PortfolioIdempotencyConflictException(eventId);
        }

        final Instant now = clock.instant();
        portfolios.recordReceipt(
                eventId,
                digest,
                payload,
                validated,
                now);
        portfolios.replaceBalances(validated, now);
        return ReceiptResult.APPLIED;
    }

    private static List<Balance> validateProvisionedBalances(
            final List<Balance> balances) {
        if (balances == null) {
            return List.of();
        }
        if (balances.size() > MAX_PROVISIONED_BALANCES) {
            throw new PortfolioContractException(
                    "Provisioning is limited to 500 balances");
        }
        final List<Balance> result =
                new ArrayList<>(balances.size());
        final Set<Integer> assetIds =
                new HashSet<>();
        for (final Balance balance : balances) {
            if (balance == null
                    || balance.assetId() == null
                    || balance.assetId() < 0) {
                throw new PortfolioContractException(
                        "assetId must be non-negative");
            }
            if (balance.amount() == null
                    || balance.amount() < 0) {
                throw new PortfolioContractException(
                        "amount must be non-negative");
            }
            if (!assetIds.add(balance.assetId())) {
                throw new PortfolioContractException(
                        "Duplicate portfolio balance assetId "
                                + balance.assetId());
            }
            result.add(balance);
        }
        return result.stream()
                .sorted((left, right) -> left.assetId().compareTo(right.assetId()))
                .toList();
    }

    private static String sha256(final byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value));
        } catch (final NoSuchAlgorithmException error) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    error);
        }
    }

    enum ReceiptResult {
        APPLIED,
        DUPLICATE
    }
}
