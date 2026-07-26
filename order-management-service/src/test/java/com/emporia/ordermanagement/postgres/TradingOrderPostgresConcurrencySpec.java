package com.emporia.ordermanagement.postgres;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies the cancel-versus-fill race against a real PostgreSQL database.
 *
 * <p>This is an opt-in integration specification selected by the {@code postgres-it}
 * Maven profile. Testcontainers starts PostgreSQL, Flyway creates the production
 * schema, and Hibernate validates its mappings instead of creating test-only tables.
 *
 * <p>The important behavior under test is the {@code @Version} column on
 * {@link TradingOrder}: two transactions may read the same order version, but only
 * one is allowed to update it. The other must fail with an optimistic-lock exception.
 */
@Testcontainers
@DataJpaTest(
        showSql = false,
        properties = {
                "spring.flyway.default-schema=emporia_order_data",
                "spring.flyway.schemas=emporia_order_data",
                "spring.flyway.create-schemas=true",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.default_schema=emporia_order_data"
        }
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class TradingOrderPostgresConcurrencySpec {

    // Spring Boot obtains JDBC connection details from this temporary real database.
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private final TradingOrderRepository orders;
    private final PlatformTransactionManager transactionManager;

    @Autowired
    TradingOrderPostgresConcurrencySpec(
            TradingOrderRepository orders,
            PlatformTransactionManager transactionManager
    ) {
        this.orders = orders;
        this.transactionManager = transactionManager;
    }

    @Test
    void optimisticVersionAllowsExactlyOneOfConcurrentFillAndCancelToCommit() throws Exception {
        UUID orderId = UUID.fromString("3fe54de7-1b31-483a-8077-b5c300ae343f");

        // Commit the initial NEW order before either competing transaction begins.
        inNewTransaction(() -> orders.saveAndFlush(order(orderId)));
        Long initialVersion = inNewTransaction(() -> orders.findById(orderId).orElseThrow().getVersion());

        // "loaded" proves that both workers read the same version. "mutate" then
        // releases both workers together so their updates genuinely compete.
        CountDownLatch loaded = new CountDownLatch(2);
        CountDownLatch mutate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // One transaction tries to fill the complete order.
            Future<Attempt> fill = executor.submit(() -> attempt(
                    orderId,
                    initialVersion,
                    loaded,
                    mutate,
                    candidate -> candidate.applyFill(new BigDecimal("100.00"), new BigDecimal("25.10"))
            ));

            // The other transaction tries to cancel the same order.
            Future<Attempt> cancel = executor.submit(() -> attempt(
                    orderId,
                    initialVersion,
                    loaded,
                    mutate,
                    TradingOrder::cancel
            ));

            // Do not start either state transition until both persistence contexts
            // hold an entity carrying initialVersion.
            assertThat(loaded.await(10, TimeUnit.SECONDS))
                    .as("both transactions loaded the same entity version")
                    .isTrue();
            mutate.countDown();

            List<Attempt> attempts = List.of(
                    fill.get(20, TimeUnit.SECONDS),
                    cancel.get(20, TimeUnit.SECONDS)
            );

            // PostgreSQL accepts the first UPDATE ... WHERE entity_version = ?.
            // Hibernate observes that the second UPDATE changed zero rows and maps
            // that lost race to ObjectOptimisticLockingFailureException.
            assertThat(attempts).filteredOn(Attempt::committed).hasSize(1);
            assertThat(attempts).filteredOn(attempt -> !attempt.committed()).singleElement()
                    .extracting(Attempt::failure)
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        } finally {
            mutate.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        // Read from a fresh transaction so these assertions describe committed
        // database state rather than either worker's in-memory entity.
        PersistedState state = inNewTransaction(() -> {
            TradingOrder persisted = orders.findById(orderId).orElseThrow();
            assertThatCode(persisted::validateInvariants).doesNotThrowAnyException();
            return new PersistedState(
                    persisted.getVersion(),
                    persisted.getStatus(),
                    persisted.getTargetStatus(),
                    persisted.getTradedQuantity(),
                    persisted.getRemainingQuantity(),
                    persisted.getAverageTradePrice()
            );
        });

        // Exactly one successful update means exactly one version increment.
        assertThat(state.version()).isEqualTo(initialVersion + 1);
        assertThat(state.status()).isIn(OrderStatus.FILLED, OrderStatus.CANCELLED);
        assertThat(state.targetStatus()).isEqualTo(state.status());

        // Whichever transaction won must have persisted a complete, internally
        // consistent state; no fields from the losing transition may leak through.
        if (state.status() == OrderStatus.FILLED) {
            assertThat(state.tradedQuantity()).isEqualByComparingTo("100.00");
            assertThat(state.remainingQuantity()).isEqualByComparingTo("0.00");
            assertThat(state.averageTradePrice()).isEqualByComparingTo("25.100000");
        } else {
            assertThat(state.tradedQuantity()).isEqualByComparingTo("0.00");
            assertThat(state.remainingQuantity()).isEqualByComparingTo("100.00");
            assertThat(state.averageTradePrice()).isNull();
        }
    }

    @Test
    void venueFillCanCommitAfterCancelRequestBeforeCancelConfirmation() {
        UUID orderId = UUID.fromString("86b8f7ee-c07d-414f-93fe-a239523b4ef6");
        inNewTransaction(() -> orders.saveAndFlush(order(orderId)));

        inNewTransaction(() -> {
            TradingOrder pending = orders.findById(orderId).orElseThrow();
            pending.requestCancel();
            orders.saveAndFlush(pending);
        });
        inNewTransaction(() -> {
            TradingOrder executing = orders.findById(orderId).orElseThrow();
            executing.applyFill(new BigDecimal("40.00"), new BigDecimal("25.10"));
            orders.saveAndFlush(executing);
        });
        inNewTransaction(() -> {
            TradingOrder cancelling = orders.findById(orderId).orElseThrow();
            cancelling.confirmCancel();
            orders.saveAndFlush(cancelling);
        });

        PersistedState state = inNewTransaction(() -> {
            TradingOrder persisted = orders.findById(orderId).orElseThrow();
            assertThatCode(persisted::validateInvariants).doesNotThrowAnyException();
            return new PersistedState(
                    persisted.getVersion(),
                    persisted.getStatus(),
                    persisted.getTargetStatus(),
                    persisted.getTradedQuantity(),
                    persisted.getRemainingQuantity(),
                    persisted.getAverageTradePrice()
            );
        });
        assertThat(state.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(state.targetStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(state.tradedQuantity()).isEqualByComparingTo("40.00");
        assertThat(state.remainingQuantity()).isEqualByComparingTo("60.00");
        assertThat(state.averageTradePrice()).isEqualByComparingTo("25.100000");
    }

    private Attempt attempt(
            UUID orderId,
            Long expectedVersion,
            CountDownLatch loaded,
            CountDownLatch mutate,
            Transition transition
    ) {
        try {
            // REQUIRES_NEW gives each worker its own transaction and persistence
            // context, matching two independent application requests.
            inNewTransaction(() -> {
                TradingOrder candidate = orders.findById(orderId).orElseThrow();
                assertThat(candidate.getVersion()).isEqualTo(expectedVersion);
                loaded.countDown();
                await(mutate);
                transition.apply(candidate);

                // Flush inside this transaction so an optimistic-lock failure is
                // captured by this worker rather than deferred beyond the attempt.
                orders.saveAndFlush(candidate);
            });
            return Attempt.success();
        } catch (Throwable failure) {
            return Attempt.failed(failure);
        }
    }

    private void inNewTransaction(Runnable action) {
        transactionTemplate().executeWithoutResult(ignored -> action.run());
    }

    private <T> T inNewTransaction(java.util.concurrent.Callable<T> action) {
        return transactionTemplate().execute(ignored -> {
            try {
                return action.call();
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private TransactionTemplate transactionTemplate() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        // The test method itself is deliberately non-transactional. Every call here
        // therefore has an explicit commit/rollback boundary visible to PostgreSQL.
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to start concurrent mutation");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to start concurrent mutation", interrupted);
        }
    }

    private static TradingOrder order(UUID orderId) {
        return new TradingOrder(
                orderId,
                "postgres-concurrency-test-user",
                listing(),
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("100.00"),
                new BigDecimal("25.00"),
                "DMA",
                "postgres-cancel-fill-race",
                null,
                orderId,
                "{}"
        );
    }

    private static ListingSnapshot listing() {
        return new ListingSnapshot(
                1,
                1,
                "AAPL",
                "Apple Inc.",
                "AAPL",
                "XNAS",
                "Nasdaq",
                "US",
                "USD",
                new BigDecimal("0.01"),
                new BigDecimal("0.01"),
                new BigDecimal("200.00"),
                new BigDecimal("198.00")
        );
    }

    @FunctionalInterface
    private interface Transition {
        void apply(TradingOrder order);
    }

    private record Attempt(boolean committed, Throwable failure) {
        private static Attempt success() {
            return new Attempt(true, null);
        }

        private static Attempt failed(Throwable failure) {
            return new Attempt(false, failure);
        }
    }

    private record PersistedState(
            Long version,
            OrderStatus status,
            OrderStatus targetStatus,
            BigDecimal tradedQuantity,
            BigDecimal remainingQuantity,
            BigDecimal averageTradePrice
    ) {
    }
}
