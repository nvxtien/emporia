package com.emporia.portfolio;

import com.emporia.portfolio.PortfolioContracts.Balance;
import com.emporia.portfolio.PortfolioContracts.LatestReceipt;
import com.emporia.portfolio.PortfolioContracts.PortfolioState;
import com.emporia.portfolio.PortfolioContracts.RiskSeed;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
class PortfolioRepository implements PortfolioStore {

    private final JdbcTemplate jdbc;

    PortfolioRepository(final JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Seeds the matching engine from the settled balance, not the available
     * one. The engine is seeded with an empty book and therefore no margin
     * holds, so seeding it from a figure that still had holds subtracted lost
     * that margin for good.
     */
    @Override
    public RiskSeed load(final long clientId) {
        final StateRow state = findState(clientId);
        return new RiskSeed(
                PortfolioContracts.SCHEMA_VERSION,
                clientId,
                state.firstTransactionId(),
                List.copyOf(settledBalances(clientId)));
    }

    @Override
    public PortfolioState state(final long clientId) {
        final StateRow state = findState(clientId);
        return new PortfolioState(
                PortfolioContracts.SCHEMA_VERSION,
                clientId,
                state.firstTransactionId(),
                state.updatedAt(),
                List.copyOf(balances(clientId)),
                latestReceipt(clientId));
    }

    @Override
    public boolean exists(final long clientId) {
        final Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM portfolio_state WHERE client_id = ?",
                Integer.class,
                clientId);
        return count != null && count > 0;
    }

    @Override
    public PortfolioState provision(
            final long clientId,
            final long firstTransactionId,
            final List<Balance> balances,
            final Instant updatedAt) {
        try {
            jdbc.update(
                    """
                    INSERT INTO portfolio_state (
                        client_id,
                        first_transaction_id,
                        updated_at
                    )
                    VALUES (?, ?, ?)
                    """,
                    clientId,
                    firstTransactionId,
                    Timestamp.from(updatedAt));
        } catch (final DuplicateKeyException error) {
            throw new PortfolioAlreadyExistsException(clientId, error);
        }

        if (!balances.isEmpty()) {
            jdbc.batchUpdate(
                    """
                    INSERT INTO portfolio_balance (
                        client_id,
                        asset_id,
                        available_balance,
                        settled_balance
                    )
                    VALUES (?, ?, ?, ?)
                    """,
                    balances.stream()
                            .map(balance -> new Object[] {
                                    clientId,
                                    balance.assetId(),
                                    balance.amount(),
                                    balance.amount()
                            })
                            .toList());
        }
        return new PortfolioState(
                PortfolioContracts.SCHEMA_VERSION,
                clientId,
                firstTransactionId,
                updatedAt,
                List.copyOf(balances),
                null);
    }

    @Override
    public void lockClient(final long clientId) {
        try {
            jdbc.queryForObject(
                    """
                    SELECT client_id
                    FROM portfolio_state
                    WHERE client_id = ?
                    FOR UPDATE
                    """,
                    Long.class,
                    clientId);
        } catch (final EmptyResultDataAccessException error) {
            throw new PortfolioNotFoundException(
                    clientId,
                    error);
        }
    }

    @Override
    public PortfolioReceipt findReceipt(final String eventId) {
        final List<PortfolioReceipt> receipts = jdbc.query(
                """
                SELECT payload_sha256, payload
                FROM received_portfolio_event
                WHERE event_id = ?
                """,
                (result, row) -> new PortfolioReceipt(
                        result.getString("payload_sha256"),
                        result.getBytes("payload")),
                eventId);
        return receipts.isEmpty() ? null : receipts.getFirst();
    }

    @Override
    public void recordReceipt(
            final String eventId,
            final String payloadSha256,
            final byte[] payload,
            final ValidatedPortfolioSnapshot snapshot,
            final Instant receivedAt) {
        jdbc.update(
                """
                INSERT INTO received_portfolio_event (
                    event_id,
                    exchange_id,
                    delivery_id,
                    client_id,
                    payload_sha256,
                    payload,
                    received_at,
                    change_kind
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                eventId,
                snapshot.exchangeId(),
                snapshot.deliveryId(),
                snapshot.clientId(),
                payloadSha256,
                payload,
                Timestamp.from(receivedAt),
                snapshot.settled() ? "SETTLED" : "RESERVED");
    }

    /**
     * Replaces the available balance from every snapshot, and the settled
     * balance only from a settled one.
     *
     * <p>A reservation snapshot reports what is spendable while margin is held,
     * which is not what the engine should be seeded with. Its settled figures
     * are therefore carried forward from what is already stored rather than
     * taken from the snapshot.
     */
    @Override
    public void replaceBalances(
            final ValidatedPortfolioSnapshot snapshot,
            final Instant updatedAt) {
        final Map<Integer, Long> settledBefore = snapshot.settled()
                ? Map.of()
                : currentSettled(snapshot.clientId());
        jdbc.update(
                "DELETE FROM portfolio_balance WHERE client_id = ?",
                snapshot.clientId());
        final List<Object[]> parameters =
                snapshot.availableBalances()
                        .entrySet()
                        .stream()
                        .map(entry -> new Object[] {
                                snapshot.clientId(),
                                entry.getKey(),
                                entry.getValue(),
                                snapshot.settled()
                                        ? entry.getValue()
                                        : settledBefore.getOrDefault(entry.getKey(), 0L)
                        })
                        .toList();
        if (!parameters.isEmpty()) {
            jdbc.batchUpdate(
                    """
                    INSERT INTO portfolio_balance (
                        client_id,
                        asset_id,
                        available_balance,
                        settled_balance
                    )
                    VALUES (?, ?, ?, ?)
                    """,
                    parameters);
        }
        jdbc.update(
                """
                UPDATE portfolio_state
                SET updated_at = ?
                WHERE client_id = ?
                """,
                Timestamp.from(updatedAt),
                snapshot.clientId());
    }

    private StateRow findState(final long clientId) {
        try {
            return jdbc.queryForObject(
                    """
                    SELECT first_transaction_id, updated_at
                    FROM portfolio_state
                    WHERE client_id = ?
                    """,
                    (result, row) -> new StateRow(
                            result.getLong("first_transaction_id"),
                            result.getTimestamp("updated_at").toInstant()),
                    clientId);
        } catch (final EmptyResultDataAccessException error) {
            throw new PortfolioNotFoundException(
                    clientId,
                    error);
        }
    }

    private List<Balance> settledBalances(final long clientId) {
        return jdbc.query(
                """
                SELECT asset_id, settled_balance
                FROM portfolio_balance
                WHERE client_id = ?
                ORDER BY asset_id
                """,
                (result, row) -> new Balance(
                        result.getInt("asset_id"),
                        result.getLong("settled_balance")),
                clientId);
    }

    private Map<Integer, Long> currentSettled(final long clientId) {
        final Map<Integer, Long> settled = new HashMap<>();
        jdbc.query(
                """
                SELECT asset_id, settled_balance
                FROM portfolio_balance
                WHERE client_id = ?
                """,
                result -> {
                    settled.put(result.getInt("asset_id"),
                            result.getLong("settled_balance"));
                },
                clientId);
        return settled;
    }

    private List<Balance> balances(final long clientId) {
        return jdbc.query(
                """
                SELECT asset_id, available_balance
                FROM portfolio_balance
                WHERE client_id = ?
                ORDER BY asset_id
                """,
                (result, row) -> new Balance(
                        result.getInt("asset_id"),
                        result.getLong("available_balance")),
                clientId);
    }

    private LatestReceipt latestReceipt(final long clientId) {
        final List<LatestReceipt> receipts = jdbc.query(
                """
                SELECT event_id, exchange_id, delivery_id, received_at
                FROM received_portfolio_event
                WHERE client_id = ?
                ORDER BY received_at DESC
                LIMIT 1
                """,
                (result, row) -> new LatestReceipt(
                        result.getString("event_id"),
                        result.getString("exchange_id"),
                        result.getLong("delivery_id"),
                        result.getTimestamp("received_at").toInstant()),
                clientId);
        return receipts.isEmpty() ? null : receipts.getFirst();
    }

    private record StateRow(
            long firstTransactionId,
            Instant updatedAt) {
    }

}
