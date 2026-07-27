package com.emporia.portfolio;

import com.emporia.portfolio.PortfolioContracts.Balance;
import com.emporia.portfolio.PortfolioContracts.RiskSeed;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
class PortfolioRepository implements PortfolioStore {

    private final JdbcTemplate jdbc;

    PortfolioRepository(final JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RiskSeed load(final long clientId) {
        final Long firstTransactionId;
        try {
            firstTransactionId = jdbc.queryForObject(
                    """
                    SELECT first_transaction_id
                    FROM portfolio_state
                    WHERE client_id = ?
                    """,
                    Long.class,
                    clientId);
        } catch (final EmptyResultDataAccessException error) {
            throw new PortfolioNotFoundException(
                    clientId,
                    error);
        }
        final List<Balance> balances = jdbc.query(
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
        return new RiskSeed(
                PortfolioContracts.SCHEMA_VERSION,
                clientId,
                firstTransactionId,
                List.copyOf(balances));
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
                    received_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                eventId,
                snapshot.exchangeId(),
                snapshot.deliveryId(),
                snapshot.clientId(),
                payloadSha256,
                payload,
                Timestamp.from(receivedAt));
    }

    @Override
    public void replaceBalances(
            final ValidatedPortfolioSnapshot snapshot,
            final Instant updatedAt) {
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
                                entry.getValue()
                        })
                        .toList();
        if (!parameters.isEmpty()) {
            jdbc.batchUpdate(
                    """
                    INSERT INTO portfolio_balance (
                        client_id,
                        asset_id,
                        available_balance
                    )
                    VALUES (?, ?, ?)
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

}
