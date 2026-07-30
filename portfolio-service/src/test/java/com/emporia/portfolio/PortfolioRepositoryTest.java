package com.emporia.portfolio;

import com.emporia.portfolio.PortfolioContracts.RiskSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

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
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(100L))).thenReturn(50L);
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
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(999L)))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThatThrownBy(() -> repository.load(999L))
                .isInstanceOf(PortfolioNotFoundException.class);
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
        ValidatedPortfolioSnapshot snapshot = new ValidatedPortfolioSnapshot("1", 10L, 100L, Map.of(1, 500L));
        repository.recordReceipt("evt-1", "sha-123", "data".getBytes(), snapshot, Instant.now());
        verify(jdbc).update(anyString(), eq("evt-1"), eq("1"), eq(10L), eq(100L), eq("sha-123"), any(byte[].class), any());
    }

    @Test
    void replaceBalancesUpdatesDatabase() {
        ValidatedPortfolioSnapshot snapshot = new ValidatedPortfolioSnapshot("1", 10L, 100L, Map.of(1, 500L));
        repository.replaceBalances(snapshot, Instant.now());
        verify(jdbc).update(eq("DELETE FROM portfolio_balance WHERE client_id = ?"), eq(100L));
        verify(jdbc).batchUpdate(anyString(), any(List.class));
    }

    @Test
    void replaceBalancesWithEmptyMap() {
        ValidatedPortfolioSnapshot snapshot = new ValidatedPortfolioSnapshot("1", 10L, 100L, Map.of());
        repository.replaceBalances(snapshot, Instant.now());
        verify(jdbc).update(eq("DELETE FROM portfolio_balance WHERE client_id = ?"), eq(100L));
    }
}
