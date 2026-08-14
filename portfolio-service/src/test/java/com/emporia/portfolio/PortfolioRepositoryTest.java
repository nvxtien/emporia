package com.emporia.portfolio;

import com.emporia.portfolio.PortfolioContracts.Balance;
import com.emporia.portfolio.PortfolioContracts.RiskSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortfolioRepositoryTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private PortfolioRepository repository;

    @BeforeEach
    void setUp() {
        repository = new PortfolioRepository(jdbc);
    }

    @Test
    void loadSuccess() throws Exception {
        Instant updatedAt = Instant.parse("2026-07-30T17:53:31.658Z");
        java.sql.ResultSet state = mock(java.sql.ResultSet.class);
        when(state.getLong("first_transaction_id")).thenReturn(50L);
        when(state.getTimestamp("updated_at")).thenReturn(Timestamp.from(updatedAt));
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(100L)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return mapper.mapRow(state, 1);
                });
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        when(rs.getInt("asset_id")).thenReturn(1);
        when(rs.getLong("available_balance")).thenReturn(500L);

        when(jdbc.query(anyString(), any(RowMapper.class), eq(100L)))
                .thenAnswer(invocation -> {
                    RowMapper<PortfolioContracts.Balance> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 1));
                });

        RiskSeed seed = repository.load(100L);
        assertThat(seed.clientId()).isEqualTo(100L);
        assertThat(seed.firstTransactionId()).isEqualTo(50L);
        assertThat(seed.balances()).hasSize(1);
    }

    @Test
    void loadClientNotFoundThrows() {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(999L)))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThatThrownBy(() -> repository.load(999L))
                .isInstanceOf(PortfolioNotFoundException.class);
    }

    @Test
    void stateReturnsBalancesAndLatestReceipt() throws Exception {
        Instant updatedAt = Instant.parse("2026-07-30T17:53:31.658Z");
        Instant receivedAt = Instant.parse("2026-07-30T17:54:31.658Z");
        java.sql.ResultSet state = mock(java.sql.ResultSet.class);
        when(state.getLong("first_transaction_id")).thenReturn(50L);
        when(state.getTimestamp("updated_at")).thenReturn(Timestamp.from(updatedAt));
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(100L)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return mapper.mapRow(state, 1);
                });
        java.sql.ResultSet balance = mock(java.sql.ResultSet.class);
        when(balance.getInt("asset_id")).thenReturn(1);
        when(balance.getLong("available_balance")).thenReturn(500L);
        java.sql.ResultSet receipt = mock(java.sql.ResultSet.class);
        when(receipt.getString("event_id")).thenReturn("evt-1");
        when(receipt.getString("exchange_id")).thenReturn("exchange-1");
        when(receipt.getLong("delivery_id")).thenReturn(10L);
        when(receipt.getTimestamp("received_at")).thenReturn(Timestamp.from(receivedAt));

        when(jdbc.query(anyString(), any(RowMapper.class), eq(100L)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    String sql = invocation.getArgument(0);
                    if (sql.contains("received_portfolio_event")) {
                        return List.of(mapper.mapRow(receipt, 1));
                    }
                    return List.of(mapper.mapRow(balance, 1));
                });

        PortfolioContracts.PortfolioState result = repository.state(100L);

        assertThat(result.clientId()).isEqualTo(100L);
        assertThat(result.updatedAt()).isEqualTo(updatedAt);
        assertThat(result.balances()).hasSize(1);
        assertThat(result.latestReceipt()).isNotNull();
        assertThat(result.latestReceipt().eventId()).isEqualTo("evt-1");
    }

    @Test
    void lockClientSuccessAndNotFound() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(100L))).thenReturn(100L);
        repository.lockClient(100L);

        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(999L)))
                .thenThrow(new EmptyResultDataAccessException(1));
        assertThatThrownBy(() -> repository.lockClient(999L))
                .isInstanceOf(PortfolioNotFoundException.class);
    }

    @Test
    void existsReturnsWhetherStateRowExists() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(100L))).thenReturn(1);
        assertThat(repository.exists(100L)).isTrue();

        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(101L))).thenReturn(0);
        assertThat(repository.exists(101L)).isFalse();
    }

    @Test
    void provisionCreatesStateAndBalances() {
        Instant updatedAt = Instant.parse("2026-07-30T17:53:31.658Z");

        PortfolioContracts.PortfolioState result = repository.provision(
                100L,
                50L,
                List.of(new Balance(1, 500L), new Balance(2, 100L)),
                updatedAt);

        verify(jdbc).update(anyString(), eq(100L), eq(50L), eq(Timestamp.from(updatedAt)));
        verify(jdbc).batchUpdate(anyString(), any(List.class));
        assertThat(result.clientId()).isEqualTo(100L);
        assertThat(result.firstTransactionId()).isEqualTo(50L);
        assertThat(result.updatedAt()).isEqualTo(updatedAt);
        assertThat(result.balances()).containsExactly(new Balance(1, 500L), new Balance(2, 100L));
    }

    @Test
    void provisionTranslatesDuplicateStateRows() {
        when(jdbc.update(anyString(), eq(100L), eq(50L), any()))
                .thenThrow(new DuplicateKeyException("duplicate"));

        assertThatThrownBy(() -> repository.provision(100L, 50L, List.of(), Instant.now()))
                .isInstanceOf(PortfolioAlreadyExistsException.class);
    }

    @Test
    void findReceiptReturnsReceiptOrNull() throws Exception {
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        when(rs.getString("payload_sha256")).thenReturn("sha-123");
        when(rs.getBytes("payload")).thenReturn("payload".getBytes());

        when(jdbc.query(anyString(), any(RowMapper.class), eq("evt-1")))
                .thenAnswer(invocation -> {
                    RowMapper<PortfolioReceipt> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 1));
                });

        assertThat(repository.findReceipt("evt-1")).isNotNull();

        when(jdbc.query(anyString(), any(RowMapper.class), eq("evt-missing")))
                .thenReturn(List.of());
        assertThat(repository.findReceipt("evt-missing")).isNull();
    }

    @Test
    void recordReceiptUpdatesDatabase() {
        ValidatedPortfolioSnapshot snapshot = new ValidatedPortfolioSnapshot("1", 10L, 100L, true, Map.of(1, 500L));
        repository.recordReceipt("evt-1", "sha-123", "data".getBytes(), snapshot, Instant.now());
        verify(jdbc).update(anyString(), eq("evt-1"), eq("1"), eq(10L), eq(100L), eq("sha-123"), any(byte[].class), any(), eq("SETTLED"));
    }

    @Test
    void replaceBalancesUpdatesDatabase() {
        ValidatedPortfolioSnapshot snapshot = new ValidatedPortfolioSnapshot("1", 10L, 100L, true, Map.of(1, 500L));
        repository.replaceBalances(snapshot, Instant.now());
        verify(jdbc).update(eq("DELETE FROM portfolio_balance WHERE client_id = ?"), eq(100L));
        verify(jdbc).batchUpdate(anyString(), any(List.class));
    }

    @Test
    void replaceBalancesWithEmptyMap() {
        ValidatedPortfolioSnapshot snapshot = new ValidatedPortfolioSnapshot("1", 10L, 100L, true, Map.of());
        repository.replaceBalances(snapshot, Instant.now());
        verify(jdbc).update(eq("DELETE FROM portfolio_balance WHERE client_id = ?"), eq(100L));
    }
}
