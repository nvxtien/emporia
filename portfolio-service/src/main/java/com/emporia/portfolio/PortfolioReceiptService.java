package com.emporia.portfolio;

import com.emporia.portfolio.PortfolioContracts.RiskSeed;
import com.emporia.portfolio.PortfolioContracts.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;

@Service
class PortfolioReceiptService {

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
