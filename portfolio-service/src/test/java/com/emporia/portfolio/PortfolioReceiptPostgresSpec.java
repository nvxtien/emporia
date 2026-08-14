package com.emporia.portfolio;

import com.emporia.portfolio.PortfolioContracts.Balance;
import com.emporia.portfolio.PortfolioContracts.Snapshot;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@JdbcTest(properties = {
        "spring.flyway.default-schema=emporia_portfolio",
        "spring.flyway.schemas=emporia_portfolio",
        "spring.flyway.create-schemas=true",
        "spring.datasource.hikari.schema=emporia_portfolio"
})
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        PortfolioRepository.class,
        PortfolioSnapshotValidator.class,
        PortfolioReceiptService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class PortfolioReceiptPostgresSpec {

    /**
     * {@code @JdbcTest} only slices in JDBC-related auto-configuration, so
     * PortfolioReceiptService's {@code @Autowired} constructor has no
     * ObservationRegistry candidate without this - a NOOP registry is enough
     * since this spec asserts persistence behavior, not observations.
     */
    @TestConfiguration
    static class ObservationRegistryConfig {
        @Bean
        ObservationRegistry observationRegistry() {
            return ObservationRegistry.NOOP;
        }
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    private final PortfolioReceiptService service;
    private final JdbcTemplate jdbc;

    @Autowired
    PortfolioReceiptPostgresSpec(
            final PortfolioReceiptService service,
            final JdbcTemplate jdbc) {
        this.service = service;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void seedPortfolio() {
        jdbc.update("DELETE FROM received_portfolio_event");
        jdbc.update("DELETE FROM portfolio_balance");
        jdbc.update("DELETE FROM portfolio_state");
        jdbc.update(
                """
                INSERT INTO portfolio_state (
                    client_id,
                    first_transaction_id
                ) VALUES (101, 7001)
                """);
        jdbc.update(
                """
                INSERT INTO portfolio_balance (
                    client_id,
                    asset_id,
                    available_balance
                ) VALUES (101, 840, 500)
                """);
    }

    @Test
    void commitsReceiptAndBalanceReplacementExactlyOnce() {
        final byte[] payload =
                "same-payload".getBytes(StandardCharsets.UTF_8);

        assertThat(apply(payload))
                .isEqualTo(
                        PortfolioReceiptService.ReceiptResult.APPLIED);
        assertThat(apply(payload))
                .isEqualTo(
                        PortfolioReceiptService.ReceiptResult.DUPLICATE);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM received_portfolio_event",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT available_balance
                FROM portfolio_balance
                WHERE client_id = 101 AND asset_id = 20001
                """,
                Long.class)).isEqualTo(5L);
    }

    @Test
    void serializesConcurrentDuplicateReceipts() throws Exception {
        final byte[] payload =
                "same-payload".getBytes(StandardCharsets.UTF_8);
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService executor =
                Executors.newFixedThreadPool(2);
        try {
            final Callable<PortfolioReceiptService.ReceiptResult> call =
                    () -> {
                        start.await(10, TimeUnit.SECONDS);
                        return apply(payload);
                    };
            final Future<PortfolioReceiptService.ReceiptResult> first =
                    executor.submit(call);
            final Future<PortfolioReceiptService.ReceiptResult> second =
                    executor.submit(call);
            start.countDown();

            assertThat(List.of(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(
                            PortfolioReceiptService.ReceiptResult.APPLIED,
                            PortfolioReceiptService.ReceiptResult.DUPLICATE);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(
                    10,
                    TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rejectsConflictingContentWithoutChangingBalances() {
        apply("first".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> apply(
                "different".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(
                        PortfolioIdempotencyConflictException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM received_portfolio_event",
                Integer.class)).isEqualTo(1);
    }

    /**
     * The risk seed must not carry margin holds. The engine is onboarded with
     * an empty book, so seeding it from a hold-adjusted figure lost that margin
     * for good - measured on the local stack as a client drifting from
     * 999,999,999,999 down to 999,291,109,999 against 72,002 resting orders.
     */
    @Test
    void reservationMovesTheAvailableBalanceButNotTheSeed() {
        applyChange(20, 400L, "settled", "SETTLED");
        assertThat(assetBalance("available_balance")).isEqualTo(400L);
        assertThat(assetBalance("settled_balance")).isEqualTo(400L);

        // Margin held against a resting order: spendable drops, nothing settled.
        applyChange(21, 250L, "hold", "RESERVED");
        assertThat(assetBalance("available_balance")).isEqualTo(250L);
        assertThat(assetBalance("settled_balance")).isEqualTo(400L);

        assertThat(service.load(101L).balances().stream()
                .filter(balance -> balance.assetId() == 20_001)
                .findFirst().orElseThrow().amount()).isEqualTo(400L);
    }

    private void applyChange(final long deliveryId, final long amount,
                             final String payload, final String change) {
        service.apply(deliveryId, 101, "exchange-1:" + deliveryId + ":101",
                payload.getBytes(StandardCharsets.UTF_8),
                new Snapshot(PortfolioContracts.SCHEMA_VERSION, "exchange-1",
                        deliveryId, 101L, change,
                        List.of(new Balance(840, 0L), new Balance(20_001, amount))));
    }

    private long assetBalance(final String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM portfolio_balance "
                        + "WHERE client_id = 101 AND asset_id = 20001",
                Long.class);
    }

    private PortfolioReceiptService.ReceiptResult apply(
            final byte[] payload) {
        return service.apply(
                13,
                101,
                "exchange-1:13:101",
                payload,
                new Snapshot(
                        PortfolioContracts.SCHEMA_VERSION,
                        "exchange-1",
                        13L,
                        101L,
                        "SETTLED",
                        List.of(
                                new Balance(840, 0L),
                                new Balance(20_001, 5L))));
    }
}
