package com.emporia.ordermanagement.postgres;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies that an order's state machine survives a real PostgreSQL round trip.
 *
 * <p>This is an opt-in integration specification selected by the
 * {@code postgres-it} Maven profile. Testcontainers starts PostgreSQL, Flyway
 * creates the production schema, and Hibernate validates its mappings instead of
 * creating test-only tables - so a mapping that drifts from the migrations fails
 * here rather than in production.
 *
 * <h2>What this used to be</h2>
 * <p>It was {@code TradingOrderPostgresConcurrencySpec}, and its main test
 * asserted that of two transactions updating one order at once, exactly one
 * commits and the other fails an optimistic-lock check. That test is gone with
 * the {@code @Version} annotation it exercised: order state changes all run on
 * the single Disruptor writer thread, so the race it constructed cannot occur,
 * and the writes that would carry the guard go out through raw JDBC where
 * Hibernate is not involved. See {@link TradingOrder#recordRevision()}.
 *
 * <p>What remains is sequential and still true - a venue fill landing between a
 * cancel request and its confirmation - which is a domain question rather than a
 * locking one, and one this system does hit.
 *
 * <h2>Two things this specification needs to load at all</h2>
 * <p>Both were broken by the merge that folded execution-service into this
 * application, and both silently, because the profile is opt-in.
 *
 * <p>{@code @ServiceConnection} feeds Boot's auto-configured DataSource and
 * stopped reaching this application when {@code OmsDataSourceConfig} began
 * declaring an explicit one, so Flyway dialled the compose-file default and
 * failed with {@code role "postgres" does not exist}. Hence the explicit
 * property binding below.
 *
 * <p>The component scan was the other: naming the sibling packages on a
 * standalone {@code @ComponentScan} dropped {@code TypeExcludeFilter}, which is
 * what makes a slice a slice, so this context tried to build the whole
 * application - see {@code OrderManagementServiceApplication}.
 */
@Testcontainers
// The application class carries @EnableCaching, which demands a CacheManager
// that a persistence slice has no reason to auto-configure. With
// spring.cache.type=none below this yields a NoOpCacheManager, which is all
// @EnableCaching needs to be satisfied.
@ImportAutoConfiguration(CacheAutoConfiguration.class)
@DataJpaTest(
        showSql = false,
        properties = {
                "spring.flyway.default-schema=emporia_order_data",
                "spring.flyway.schemas=emporia_order_data",
                "spring.flyway.create-schemas=true",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.default_schema=emporia_order_data",
                "spring.cache.type=none"
        }
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class TradingOrderPostgresPersistenceSpec {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void useTheContainer(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("emporia.execution.datasource.url", postgres::getJdbcUrl);
        registry.add("emporia.execution.datasource.username", postgres::getUsername);
        registry.add("emporia.execution.datasource.password", postgres::getPassword);
    }

    private final TradingOrderRepository orders;
    private final PlatformTransactionManager transactionManager;

    @Autowired
    TradingOrderPostgresPersistenceSpec(
            TradingOrderRepository orders,
            PlatformTransactionManager transactionManager
    ) {
        this.orders = orders;
        this.transactionManager = transactionManager;
    }

    /**
     * A cancellation is requested, the venue fills part of the order before it
     * hears about that, and the cancellation is then confirmed. The order must
     * end cancelled while keeping what it actually traded.
     */
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

    /**
     * The repository must insert an order it has never seen. It did not: with
     * {@code @Version} present, Spring Data decided new from existing by
     * {@code version == null}, the constructor assigned 0, and every save of a
     * new order took the merge path - a select, no insert, then an
     * optimistic-lock error for a conflict that never happened.
     */
    @Test
    void savingAnOrderTheDatabaseHasNeverSeenInsertsIt() {
        UUID orderId = UUID.randomUUID();

        inNewTransaction(() -> orders.saveAndFlush(order(orderId)));

        assertThat(inNewTransaction(() -> orders.findById(orderId).isPresent())).isTrue();
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

    private static TradingOrder order(UUID orderId) {
        return new TradingOrder(
                orderId,
                "postgres-persistence-test-user",
                listing(),
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("100.00"),
                new BigDecimal("25.00"),
                "DMA",
                "postgres-cancel-fill-sequence",
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

    private record PersistedState(
            OrderStatus status,
            OrderStatus targetStatus,
            BigDecimal tradedQuantity,
            BigDecimal remainingQuantity,
            BigDecimal averageTradePrice
    ) {
    }
}
