package com.emporia.ordermanagement.postgres;

import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.execution.DurableOrderOutputDispatcher;
import com.emporia.execution.ShardedOrderDispatcher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PostgreSQL drill for outbox ordering, retry, and retention. */
@Testcontainers
public class DurableOrderOutputPostgresSpec {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private ShardedOrderDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS emporia_order_data");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS emporia_order_data.order_delivery_outbox (
                    sequence_id BIGSERIAL PRIMARY KEY,
                    event_id UUID NOT NULL UNIQUE,
                    schema_version INTEGER NOT NULL,
                    command_id UUID NOT NULL,
                    order_id UUID NOT NULL,
                    user_subject VARCHAR(200),
                    desk_id VARCHAR(100),
                    event_type VARCHAR(32) NOT NULL,
                    order_version BIGINT NOT NULL,
                    order_status VARCHAR(24) NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    payload TEXT NOT NULL,
                    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    lease_until TIMESTAMP WITH TIME ZONE,
                    last_error VARCHAR(2000),
                    delivered_at TIMESTAMP WITH TIME ZONE
                )
                """);
        jdbc.update("TRUNCATE emporia_order_data.order_delivery_outbox RESTART IDENTITY");
        dispatcher = mock(ShardedOrderDispatcher.class);
    }

    @Test
    void retryDoesNotPassAnEarlierEventForTheSameOrder() {
        UUID orderId = UUID.randomUUID();
        OrderDomainEvent first = event(orderId, "CREATED");
        OrderDomainEvent second = event(orderId, "MODIFIED");
        insert(first);
        insert(second);

        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("venue unavailable"));
        when(dispatcher.dispatch(any())).thenReturn(failed);
        DurableOrderOutputDispatcher output = output();

        output.poll();

        assertThat(jdbc.queryForObject("SELECT status FROM emporia_order_data.order_delivery_outbox WHERE event_id = ?",
                String.class, first.eventId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM emporia_order_data.order_delivery_outbox WHERE event_id = ?",
                Integer.class, first.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM emporia_order_data.order_delivery_outbox WHERE event_id = ?",
                Integer.class, second.eventId())).isZero();
    }

    @Test
    void deliveredRowsAreRetainedOnlyWithinTheConfiguredWindow() {
        OrderDomainEvent old = event(UUID.randomUUID(), "CREATED");
        insert(old);
        jdbc.update("""
                UPDATE emporia_order_data.order_delivery_outbox
                SET status = 'DELIVERED', delivered_at = CURRENT_TIMESTAMP - INTERVAL '8 days'
                WHERE event_id = ?
                """, old.eventId());

        output().deleteDelivered();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM emporia_order_data.order_delivery_outbox WHERE event_id = ?",
                Long.class, old.eventId())).isZero();
    }

    private DurableOrderOutputDispatcher output() {
        return new DurableOrderOutputDispatcher(jdbc, dispatcher, new SimpleMeterRegistry(), 7);
    }

    private void insert(OrderDomainEvent event) {
        jdbc.update("""
                INSERT INTO emporia_order_data.order_delivery_outbox
                (event_id, schema_version, command_id, order_id, user_subject, desk_id,
                 event_type, order_version, order_status, occurred_at, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, event.eventId(), event.schemaVersion(), event.commandId(), event.orderId(),
                event.userSubject(), event.deskId(), event.eventType(), event.orderVersion(),
                event.status().name(), event.occurredAt(), event.payload());
    }

    private static OrderDomainEvent event(UUID orderId, String type) {
        return new OrderDomainEvent(1, UUID.randomUUID(), UUID.randomUUID(), orderId,
                "trader", "desk", type, 1, OrderStatus.LIVE, Instant.now(), "{}");
    }
}
