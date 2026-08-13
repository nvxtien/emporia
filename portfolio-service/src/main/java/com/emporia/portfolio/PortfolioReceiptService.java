package com.emporia.portfolio;

import com.emporia.portfolio.PortfolioContracts.Balance;
import com.emporia.portfolio.PortfolioContracts.PortfolioState;
import com.emporia.portfolio.PortfolioContracts.RiskSeed;
import com.emporia.portfolio.PortfolioContracts.Snapshot;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
    private final ObservationRegistry observations;

    PortfolioReceiptService(
            final PortfolioStore portfolios,
            final PortfolioSnapshotValidator validator) {
        this(portfolios, validator, Clock.systemUTC(), ObservationRegistry.NOOP);
    }

    @Autowired
    PortfolioReceiptService(
            final PortfolioStore portfolios,
            final PortfolioSnapshotValidator validator,
            final ObservationRegistry observations) {
        this(portfolios, validator, Clock.systemUTC(), observations);
    }

    PortfolioReceiptService(
            final PortfolioStore portfolios,
            final PortfolioSnapshotValidator validator,
            final Clock clock,
            final ObservationRegistry observations) {
        this.portfolios = portfolios;
        this.validator = validator;
        this.clock = clock;
        this.observations = observations;
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

    /**
     * Records {@code emporia.portfolio.snapshot.apply}. The {@code lockClient}
     * row lock is the expected latency tail and sits inside this observation.
     *
     * <p>Note: {@code outcome=duplicate} extends the tag vocabulary fixed by
     * REWORK_NOTE Phase 1_1 ({@code success|rejected|timeout|error}). An
     * idempotent redelivery does succeed, but collapsing it into
     * {@code success} would hide the redelivery rate, which is exactly what the
     * idempotency contract exists to control. Still a bounded, low-cardinality
     * value. {@code outcome=stale} follows the same reasoning for a snapshot
     * whose delivery id is not newer than what is already applied: the
     * publisher's delivery transport (an async, multi-worker outbox) has no
     * ordering guarantee, so an older snapshot can arrive after a newer one
     * has already been applied. Applying it anyway would silently revert a
     * correct balance to a stale one.
     */
    @Transactional
    ReceiptResult apply(
            final long pathDeliveryId,
            final long pathClientId,
            final String eventId,
            final byte[] payload,
            final Snapshot snapshot) {
        final Observation observation = Observation
                .createNotStarted("emporia.portfolio.snapshot.apply", observations)
                .start();
        String outcome = "success";
        try {
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
                    outcome = "duplicate";
                    return ReceiptResult.DUPLICATE;
                }
                outcome = "rejected";
                throw new PortfolioIdempotencyConflictException(eventId);
            }

            if (pathDeliveryId <= portfolios.lastDeliveryId(
                    pathClientId,
                    validated.exchangeId())) {
                outcome = "stale";
                return ReceiptResult.STALE;
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
        } catch (PortfolioContractException | PortfolioIdempotencyConflictException rejection) {
            outcome = "rejected";
            observation.error(rejection);
            throw rejection;
        } catch (RuntimeException exception) {
            outcome = "error";
            observation.error(exception);
            throw exception;
        } finally {
            observation.lowCardinalityKeyValue("outcome", outcome).stop();
        }
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
        DUPLICATE,
        STALE
    }
}
